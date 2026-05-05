package com.example.myapp.launcher;

/*
 * Launcher lifecycle
 * ------------------
 * 1. Resolve APP_HOME (system prop -Dapp.home > %APPDATA%\PoS Agent on Windows >
 *    ${user.home}/.posagent). Set it as a system property so update4j's
 *    ${app.home} placeholder in config.xml resolves to it.
 * 2. Configure file logging at ${APP_HOME}/logs/launcher.log so jpackage's
 *    windowless PoS Agent.exe still leaves a forensic trail.
 * 3. First-run seeding: if ${APP_HOME} is empty, copy the bundled payload
 *    that the installer dropped at ${install_dir}/app/ over to ${APP_HOME}.
 *    This lets the app boot offline on first run.
 * 4. Try to discover the latest GitHub release and read the remote config.xml.
 * 5. Run config.update(...) which sha-256-compares each file and downloads
 *    only what changed. Logs each file + size as it streams.
 * 6. On success, persist the new config.xml to ${APP_HOME}/config.xml. On any
 *    network/parse failure we log a warning and fall through to launch with
 *    the cached config.
 * 7. Spawn a child JVM with `java -jar ${APP_HOME}/quarkus-run.jar`. Quarkus
 *    fast-jar relies on its own classloader hierarchy and assumes it's the
 *    process entry point — running it via update4j's reflective Class.forName
 *    breaks the bootstrap. Child stdout/stderr go to ${APP_HOME}/logs/quarkus.log.
 *    Blocking — returns when Quarkus exits.
 * 8. If ${APP_HOME}/.restart-pending exists, delete it and loop back to 4.
 */

import org.update4j.Configuration;
import org.update4j.service.UpdateHandler;

import java.awt.Desktop;
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

        LauncherSplash splash = LauncherSplash.createOrNull();
        if (splash != null) {
            splash.setStatus("Starting PoS Agent…");
            splash.setIndeterminate();
            splash.show();
        }

        try {
            superviseAndRun(appHome, splash);
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "[launcher] FATAL", t);
            System.err.println("[launcher] FATAL: " + t.getMessage());
            t.printStackTrace(System.err);
            if (splash != null) {
                splash.setStatus("Failed to start");
                splash.setDetail(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                try { Thread.sleep(2500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            System.exit(1);
        } finally {
            if (splash != null) splash.dispose();
        }
    }

    /**
     * Supervisor loop. Three exit reasons for the child Quarkus process:
     *   1. Clean exit (code 0, no restart marker)  → quit launcher.
     *   2. User-requested restart (marker present) → loop immediately, no backoff.
     *   3. Crash (nonzero exit, no marker)         → exponential backoff and retry.
     *
     * Backoff resets when the app stays up longer than {@link #UPTIME_RESET_MS},
     * so a flaky once-an-hour crash never lets the delay grow without bound.
     * Cap is 60 s so kiosks recover quickly even after sustained failures.
     */
    private static void superviseAndRun(Path appHome, LauncherSplash splash) throws Exception {
        long[] backoffs = {1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L};
        int crashStreak = 0;

        while (true) {
            long startedAt = System.currentTimeMillis();
            int exit = runOnce(appHome, splash);
            long uptimeMs = System.currentTimeMillis() - startedAt;

            if (consumeRestartFlag(appHome)) {
                crashStreak = 0;
                continue;
            }
            if (exit == 0) {
                LOG.info("[launcher] Clean exit, no restart pending — done.");
                return;
            }

            if (uptimeMs > UPTIME_RESET_MS) crashStreak = 0;
            long delayMs = backoffs[Math.min(crashStreak, backoffs.length - 1)];
            crashStreak++;
            LOG.warning("[launcher] App exited with code " + exit + " after "
                + uptimeMs + " ms — restart attempt #" + crashStreak
                + " in " + delayMs + " ms.");

            if (splash != null) {
                splash.setStatus("PoS Agent crashed — restarting…");
                splash.setDetail("Exit code " + exit + " · retry in " + (delayMs / 1000) + "s");
                splash.setIndeterminate();
                splash.show();
            }
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static final long UPTIME_RESET_MS = 60_000L;

    private static int runOnce(Path appHome, LauncherSplash splash) throws Exception {
        Path localConfig = appHome.resolve("config.xml");

        if (splash != null) {
            splash.setStatus("Checking for updates…");
            splash.setDetail("Contacting GitHub");
            splash.setIndeterminate();
            splash.show();
        }

        if (!Files.exists(localConfig)) {
            seedFromInstallDir(appHome);
        }

        String owner = System.getProperty("github.owner", DEFAULT_OWNER);
        String repo = System.getProperty("github.repo", DEFAULT_REPO);

        Configuration config = null;

        try {
            String tag = fetchLatestTag(owner, repo);
            if (splash != null) splash.setDetail("Latest release: " + tag);
            URI remoteConfigUri = URI.create(
                "https://github.com/" + owner + "/" + repo + "/releases/download/" + tag + "/config.xml");
            LOG.info("[launcher] Fetching remote config: " + remoteConfigUri);

            try (InputStream in = openStream(remoteConfigUri)) {
                config = Configuration.read(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            }

            LOG.info("[launcher] Running update4j update...");
            if (splash != null) {
                splash.setStatus("Applying update…");
                splash.setDetail("Comparing local files");
            }
            boolean updated = config.update(new LoggingUpdateHandler(splash));
            LOG.info("[launcher] Update finished. Files changed: " + updated);

            try (InputStream in = openStream(remoteConfigUri)) {
                Files.copy(in, localConfig, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception remoteFailure) {
            LOG.log(Level.WARNING,
                "[launcher] Remote update check failed (" + remoteFailure.getClass().getSimpleName()
                    + "): " + remoteFailure.getMessage() + " — falling back to local cached config.",
                remoteFailure);
            if (splash != null) {
                splash.setDetail("Offline — using cached version");
            }

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
        // Silence unused warning; we may log details later.
        if (config == null) throw new IllegalStateException("no configuration");
        if (splash != null) {
            splash.setStatus("Launching PoS Agent…");
            splash.setDetail("Open http://localhost:8080 once it's ready");
            splash.setIndeterminate();
        }
        int exit = launchQuarkus(appHome, splash);
        LOG.info("[launcher] Application exited with code " + exit);
        return exit;
    }

    private static int launchQuarkus(Path appHome, LauncherSplash splash) throws IOException, InterruptedException {
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
        Thread hook = new Thread(() -> {
            if (proc.isAlive()) {
                proc.destroy();
            }
        }, "myapp-launcher-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);

        // Wait for Quarkus to be reachable on 8080 (or for it to die early), then
        // open the user's browser at http://localhost:8080 and fade the splash
        // out. The launcher process keeps the EDT alive until main() returns,
        // so dispose() comes from the finally in main().
        Thread waiter = new Thread(() -> {
            if (waitForPortOrExit(proc, 8080, 60_000)) {
                if (splash != null) {
                    splash.setStatus("PoS Agent is ready");
                    splash.setDetail("Opening browser…");
                }
                openBrowser("http://localhost:8080");
                try { Thread.sleep(900); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            if (splash != null) splash.hide();
        }, "myapp-splash-waiter");
        waiter.setDaemon(true);
        waiter.start();

        try {
            return proc.waitFor();
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) { /* JVM already shutting down */ }
        }
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "[launcher] Desktop.browse failed, falling back to OS shell", e);
        }
        try {
            if (isWindows()) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else {
                String os = System.getProperty("os.name", "").toLowerCase();
                if (os.contains("mac")) new ProcessBuilder("open", url).start();
                else new ProcessBuilder("xdg-open", url).start();
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[launcher] Could not open browser: " + e.getMessage(), e);
        }
    }

    private static boolean waitForPortOrExit(Process proc, int port, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive()) return false;
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 250);
                return true;
            } catch (IOException e) {
                try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
            }
        }
        return false;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    private static Path resolveAppHome() {
        String prop = System.getProperty("app.home");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        String appdata = System.getenv("APPDATA");
        if (appdata != null && !appdata.isBlank()) {
            return Path.of(appdata, "PoS Agent");
        }
        return Path.of(System.getProperty("user.home"), ".posagent");
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

        private final LauncherSplash splash;
        private String currentFile;
        private long currentSize;

        LoggingUpdateHandler(LauncherSplash splash) {
            this.splash = splash;
        }

        @Override
        public long version() {
            return 1L;
        }

        @Override
        public void startDownloads() {
            if (splash != null) {
                splash.setStatus("Downloading update…");
                splash.setProgress(0f);
            }
        }

        @Override
        public void startDownloadFile(org.update4j.FileMetadata file) {
            this.currentFile = file.getPath().getFileName().toString();
            this.currentSize = file.getSize();
            LOG.info(String.format("[launcher] downloading %s (%d bytes)", currentFile, currentSize));
            if (splash != null) {
                splash.setDetail(currentFile + " — " + formatBytes(currentSize));
            }
        }

        @Override
        public void updateDownloadProgress(float frac) {
            if (splash != null) splash.setProgress(frac);
        }

        @Override
        public void doneDownloadFile(org.update4j.FileMetadata file, Path tempFile) {
            LOG.info(String.format("[launcher] downloaded  %s", currentFile));
        }

        @Override
        public void doneDownloads() {
            if (splash != null) {
                splash.setStatus("Installing update…");
                splash.setDetail("Replacing files");
                splash.setIndeterminate();
            }
        }

        @Override
        public void failed(Throwable t) {
            LOG.log(Level.WARNING, "[launcher] update failed: " + t.getMessage(), t);
            if (splash != null) {
                splash.setStatus("Update failed");
                splash.setDetail(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            }
        }

        private static String formatBytes(long n) {
            if (n < 1024) return n + " B";
            if (n < 1024 * 1024) return String.format("%.1f KiB", n / 1024.0);
            return String.format("%.2f MiB", n / 1024.0 / 1024.0);
        }
    }
}
