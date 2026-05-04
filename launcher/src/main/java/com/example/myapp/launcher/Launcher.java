package com.example.myapp.launcher;

/*
 * Launcher lifecycle
 * ------------------
 * 1. Resolve APP_HOME (system prop -Dapp.home > %APPDATA%\MyApp on Windows >
 *    ${user.home}/.myapp). Set it as a system property so update4j's
 *    ${app.home} placeholder in config.xml resolves to it.
 * 2. Configure file logging at ${APP_HOME}/logs/launcher.log so jpackage's
 *    windowless MyApp.exe still leaves a forensic trail.
 * 3. First-run seeding: if ${APP_HOME} is empty, copy the bundled payload
 *    that the installer dropped at ${install_dir}/app/ over to ${APP_HOME}.
 *    This lets the app boot offline on first run.
 * 4. Try to discover the latest GitHub release and read the remote config.xml.
 * 5. Run config.update(...) which sha-256-compares each file and downloads
 *    only what changed. Logs each file + size as it streams.
 * 6. On success, persist the new config.xml to ${APP_HOME}/config.xml. On any
 *    network/parse failure we log a warning and fall through to launch with
 *    the cached config.
 * 7. config.launch() loads the dynamic classpath and runs Quarkus
 *    (entry point declared in config.xml's default.launcher.main.class).
 *    Blocking — returns when Quarkus exits.
 * 8. If ${APP_HOME}/.restart-pending exists, delete it and loop back to 4.
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
        // Make ${app.home} resolvable inside update4j config.xml.
        System.setProperty("app.home", appHome.toString());
        configureFileLogging(appHome);

        LOG.info("[launcher] APP_HOME=" + appHome);

        try {
            do {
                runOnce(appHome);
            } while (consumeRestartFlag(appHome));
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "[launcher] FATAL", t);
            System.err.println("[launcher] FATAL: " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void runOnce(Path appHome) throws Exception {
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

        LOG.info("[launcher] Launching application...");
        config.launch();
        LOG.info("[launcher] Application exited.");
    }

    private static Path resolveAppHome() {
        String prop = System.getProperty("app.home");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        String appdata = System.getenv("APPDATA");
        if (appdata != null && !appdata.isBlank()) {
            return Path.of(appdata, "MyApp");
        }
        return Path.of(System.getProperty("user.home"), ".myapp");
    }

    /**
     * Locate the read-only payload jpackage shipped alongside the EXE.
     * jpackage layout: ${install}/runtime/ for the JRE, ${install}/app/ for
     * the contents of --input. So java.home's parent + /app is our seed.
     */
    private static Path findSeedDir() {
        String jpackagePath = System.getProperty("jpackage.app-path");
        if (jpackagePath != null && !jpackagePath.isBlank()) {
            Path candidate = Path.of(jpackagePath).toAbsolutePath().getParent();
            if (candidate != null) {
                Path appDir = candidate.resolve("app");
                if (Files.isDirectory(appDir) && Files.exists(appDir.resolve("config.xml"))) {
                    return appDir;
                }
            }
        }
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            Path parent = Path.of(javaHome).getParent();
            if (parent != null) {
                Path appDir = parent.resolve("app");
                if (Files.isDirectory(appDir) && Files.exists(appDir.resolve("config.xml"))) {
                    return appDir;
                }
            }
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
        LOG.info("[launcher] Seeding " + appHome + " from " + seed);
        Files.walkFileTree(seed, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = seed.relativize(dir);
                Path target = appHome.resolve(rel.toString());
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = seed.relativize(file);
                String relStr = rel.toString().replace('\\', '/');
                // Skip launcher.jar — currently running, can't be overwritten.
                if (relStr.equals("launcher.jar")) {
                    return FileVisitResult.CONTINUE;
                }
                Path target = appHome.resolve(rel.toString());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
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
        private long currentSize;

        @Override
        public long version() {
            return 1L;
        }

        @Override
        public void startDownloadFile(org.update4j.FileMetadata file) {
            this.currentFile = file.getPath().getFileName().toString();
            this.currentSize = file.getSize();
            LOG.info(String.format("[launcher] downloading %s (%d bytes)", currentFile, currentSize));
        }

        @Override
        public void doneDownloadFile(org.update4j.FileMetadata file, Path tempFile) {
            LOG.info(String.format("[launcher] downloaded  %s", currentFile));
        }

        @Override
        public void failed(Throwable t) {
            LOG.log(Level.WARNING, "[launcher] update failed: " + t.getMessage(), t);
        }
    }
}
