package com.example.myapp.launcher;

/*
 * Launcher lifecycle
 * ------------------
 * 1. Resolve APP_HOME (-Dapp.home > %PROGRAMDATA%\PoS Agent on Windows >
 *    ~/.posagent elsewhere). Set as a system property so update4j's
 *    ${app.home} placeholder in config.xml resolves to it.
 * 2. Configure file logging at ${APP_HOME}/logs/launcher.log.
 * 3. Install a JVM shutdown hook so SCM/WinSW stop signals destroy the
 *    Quarkus child cleanly instead of leaving it orphaned.
 * 4. First-run seed: if ${APP_HOME} has no config.xml, copy the bundled
 *    payload from launcher.jar's directory (the zip extract). Lets the
 *    app boot offline on first run.
 * 5. Fetch the latest release tag from GitHub and read its config.xml.
 *    Run config.update(...) — sha-256 compares each file, downloads only
 *    deltas. On any network failure, fall through to the local cached
 *    config so a flaky network can't bring the kiosk down.
 * 6. Spawn `java -jar quarkus-run.jar` as a child JVM. Quarkus's fast-jar
 *    layout assumes it's the process entry point, so we don't run it
 *    via update4j's reflective bootstrap. Block on the child.
 * 7. When the child exits: if .restart-pending exists, loop back to 5.
 *    Otherwise exit — clean exit cleanly, crash with the child's code so
 *    WinSW's <onfailure action="restart"> handles recovery.
 */

import org.update4j.Configuration;
import org.update4j.service.UpdateHandler;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Launcher {

    private static final Logger LOG = Logger.getLogger(Launcher.class.getName());

    private static final String DEFAULT_OWNER = "jetski27";
    private static final String DEFAULT_REPO = "self-updating";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Flipped by the shutdown hook when SCM/WinSW signals a stop. The
     * supervisor loop checks this before and after every child run so
     * `sc stop PoSAgent` doesn't get ignored mid-cycle.
     */
    private static final AtomicBoolean STOPPING = new AtomicBoolean(false);
    private static volatile Process currentChild;

    private Launcher() {
    }

    public static void main(String[] args) {
        Path appHome = resolveAppHome();
        try {
            Files.createDirectories(appHome);
        } catch (IOException e) {
            System.err.println("[launcher] cannot create " + appHome + ": " + e.getMessage());
            System.exit(1);
        }
        System.setProperty("app.home", appHome.toString());
        configureFileLogging(appHome);

        LOG.info("[launcher] APP_HOME=" + appHome);

        installShutdownHook();

        try {
            superviseAndRun(appHome);
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "[launcher] FATAL", t);
            System.err.println("[launcher] FATAL: " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /**
     * SCM stop signal -> destroy the Quarkus child so its waitFor returns,
     * flip STOPPING so the supervisor loop exits cleanly. Without this the
     * child would be orphaned and SCM would force-kill the launcher after
     * stoptimeout.
     */
    private static void installShutdownHook() {
        Thread main = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            STOPPING.set(true);
            LOG.info("[launcher] Stop requested — shutting down.");
            Process child = currentChild;
            if (child != null && child.isAlive()) {
                child.destroy();
                try {
                    if (!child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        child.destroyForcibly();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    child.destroyForcibly();
                }
            }
            main.interrupt();
        }, "posagent-shutdown"));
    }

    /**
     * Two reasons to stay in the loop:
     *   1. .restart-pending marker present (dashboard "Restart" after an
     *      update) — re-run update4j and respawn.
     *   2. Otherwise exit. WinSW's <onfailure action="restart"> handles
     *      crash recovery at the service layer; we don't duplicate it.
     *      Clean exit (code 0) just exits.
     */
    private static void superviseAndRun(Path appHome) throws Exception {
        while (true) {
            if (STOPPING.get()) return;
            int exit = runOnce(appHome);
            if (STOPPING.get()) return;
            if (consumeRestartFlag(appHome)) {
                continue;
            }
            if (exit != 0) {
                LOG.warning("[launcher] Quarkus exited with code " + exit
                    + " — propagating so WinSW restarts the service.");
                System.exit(exit);
            }
            return;
        }
    }

    private static int runOnce(Path appHome) throws Exception {
        Path localConfig = appHome.resolve("config.xml");

        if (!Files.exists(localConfig)) {
            seedFromInstallDir(appHome);
        }

        String owner = System.getProperty("github.owner", DEFAULT_OWNER);
        String repo = System.getProperty("github.repo", DEFAULT_REPO);

        Configuration config = null;

        try {
            String tag = fetchLatestTag(owner, repo);
            URI remoteConfigUri = URI.create(
                "https://github.com/" + owner + "/" + repo + "/releases/download/" + tag + "/config.xml");
            LOG.info("[launcher] Fetching remote config: " + remoteConfigUri);

            try (InputStream in = openStream(remoteConfigUri)) {
                config = Configuration.read(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            }

            LOG.info("[launcher] Running update4j update...");
            boolean updated = config.update(new LoggingUpdateHandler());
            LOG.info("[launcher] Update finished. Files changed: " + updated);

            try (InputStream in = openStream(remoteConfigUri)) {
                Files.copy(in, localConfig, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception remoteFailure) {
            LOG.log(Level.WARNING,
                "[launcher] Remote update check failed (" + remoteFailure.getClass().getSimpleName()
                    + "): " + remoteFailure.getMessage() + " — falling back to local cached config.",
                remoteFailure);

            if (!Files.isRegularFile(localConfig)) {
                throw new IllegalStateException(
                    "No remote update available and no local config at " + localConfig
                        + ". Cannot start the application.",
                    remoteFailure);
            }
            try (InputStream in = Files.newInputStream(localConfig)) {
                config = Configuration.read(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            }
        }

        if (config == null) throw new IllegalStateException("no configuration");
        LOG.info("[launcher] Launching application...");
        int exit = launchQuarkus(appHome);
        LOG.info("[launcher] Application exited with code " + exit);
        return exit;
    }

    private static int launchQuarkus(Path appHome) throws IOException, InterruptedException {
        Path javaBin = Path.of(
            System.getProperty("java.home"),
            "bin",
            isWindows() ? "java.exe" : "java"
        );
        Path quarkusJar = appHome.resolve("quarkus-run.jar");
        if (!Files.isRegularFile(quarkusJar)) {
            throw new IllegalStateException("quarkus-run.jar not found at " + quarkusJar);
        }
        Path logDir = appHome.resolve("logs");
        Files.createDirectories(logDir);

        ProcessBuilder pb = new ProcessBuilder(
            javaBin.toString(),
            "-Dapp.home=" + appHome,
            "-jar", quarkusJar.toString()
        );
        pb.directory(appHome.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(logDir.resolve("quarkus.log").toFile());

        LOG.info("[launcher] Starting Quarkus: " + pb.command());
        Process proc = pb.start();
        currentChild = proc;
        try {
            return proc.waitFor();
        } finally {
            currentChild = null;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    private static Path resolveAppHome() {
        String prop = System.getProperty("app.home");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        if (isWindows()) {
            String programData = System.getenv("ProgramData");
            if (programData != null && !programData.isBlank()) {
                return Path.of(programData, "PoS Agent");
            }
        }
        return Path.of(System.getProperty("user.home"), ".posagent");
    }

    /**
     * Locate the seed payload shipped alongside launcher.jar. With the
     * zip-distribution layout, launcher.jar lives at the install root next
     * to config.xml + quarkus-run.jar. We resolve the jar's parent dir
     * and use it as the seed if it has the expected files.
     */
    private static Path findSeedDir() {
        try {
            Path self = Path.of(Launcher.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toAbsolutePath();
            Path parent = self.getParent();
            if (parent != null && Files.exists(parent.resolve("config.xml"))
                && Files.exists(parent.resolve("quarkus-run.jar"))) {
                return parent;
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "[launcher] Couldn't resolve self path", e);
        }
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.exists(cwd.resolve("config.xml")) && Files.exists(cwd.resolve("quarkus-run.jar"))) {
            return cwd;
        }
        return null;
    }

    private static void seedFromInstallDir(Path appHome) throws IOException {
        Path seed = findSeedDir();
        if (seed == null) {
            LOG.info("[launcher] No seed directory found — first launch will need network.");
            return;
        }
        if (seed.equals(appHome)) return;
        LOG.info("[launcher] Seeding " + appHome + " from " + seed);
        Files.walkFileTree(seed, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(appHome.resolve(seed.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String rel = seed.relativize(file).toString().replace('\\', '/');
                // Skip launcher.jar itself — running, can't be overwritten anyway.
                if (rel.equals("launcher.jar")) return FileVisitResult.CONTINUE;
                Files.copy(file, appHome.resolve(rel), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean consumeRestartFlag(Path appHome) throws IOException {
        Path marker = appHome.resolve(".restart-pending");
        if (Files.exists(marker)) {
            Files.deleteIfExists(marker);
            LOG.info("[launcher] Restart pending — relaunching.");
            return true;
        }
        return false;
    }

    private static void configureFileLogging(Path appHome) {
        try {
            Path logDir = appHome.resolve("logs");
            Files.createDirectories(logDir);
            FileHandler fh = new FileHandler(logDir.resolve("launcher.log").toString(), 1_048_576, 3, true);
            fh.setFormatter(new SimpleFormatter());
            fh.setLevel(Level.ALL);
            Logger root = Logger.getLogger("");
            root.addHandler(fh);
            root.setLevel(Level.INFO);
        } catch (IOException e) {
            System.err.println("[launcher] Could not initialise file logging: " + e.getMessage());
        }
    }

    private static String fetchLatestTag(String owner, String repo) throws IOException, InterruptedException {
        URI api = URI.create("https://api.github.com/repos/" + owner + "/" + repo + "/releases/latest");
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        HttpRequest req = HttpRequest.newBuilder(api)
            .timeout(READ_TIMEOUT)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "myapp-launcher")
            .GET()
            .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("GitHub API returned HTTP " + resp.statusCode() + " for " + api);
        }
        Matcher m = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").matcher(resp.body());
        if (!m.find()) {
            throw new IOException("tag_name not found in GitHub response");
        }
        return m.group(1);
    }

    private static InputStream openStream(URI uri) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        HttpRequest req = HttpRequest.newBuilder(uri)
            .timeout(READ_TIMEOUT)
            .header("User-Agent", "myapp-launcher")
            .GET()
            .build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            resp.body().close();
            throw new IOException("HTTP " + resp.statusCode() + " fetching " + uri);
        }
        return resp.body();
    }

    private static final class LoggingUpdateHandler implements UpdateHandler {

        private String currentFile;

        @Override
        public long version() {
            return 1L;
        }

        @Override
        public void startDownloadFile(org.update4j.FileMetadata file) {
            this.currentFile = file.getPath().getFileName().toString();
            LOG.info(String.format("[launcher] downloading %s (%d bytes)", currentFile, file.getSize()));
        }

        @Override
        public void doneDownloadFile(org.update4j.FileMetadata file, Path tempFile) {
            LOG.info("[launcher] downloaded  " + currentFile);
        }

        @Override
        public void failed(Throwable t) {
            LOG.log(Level.WARNING, "[launcher] update failed: " + t.getMessage(), t);
        }
    }
}
