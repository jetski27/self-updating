package com.example.myapp.launcher;

/*
 * Launcher lifecycle
 * ------------------
 * 1. Detect mode: desktop (default) or service (--service flag, or PoSAgent_SERVICE=1
 *    env var set by WinSW). Service mode picks %PROGRAMDATA%\PoS Agent for state and
 *    suppresses splash + browser open since the process runs in Session 0.
 * 2. Resolve APP_HOME (system prop -Dapp.home > mode-specific default).
 *    Set it as a system property so update4j's ${app.home} placeholder in config.xml
 *    resolves to it. In service mode, migrate state from a pre-existing
 *    %APPDATA%\PoS Agent so upgraders don't lose their cached config.
 * 3. Configure file logging at ${APP_HOME}/logs/launcher.log so jpackage's
 *    windowless PoS Agent.exe still leaves a forensic trail.
 * 4. First-run seeding: if ${APP_HOME} is empty, copy the bundled payload
 *    that the installer dropped at ${install_dir}/app/ over to ${APP_HOME}.
 *    This lets the app boot offline on first run.
 * 5. Try to discover the latest GitHub release and read the remote config.xml.
 * 6. Run config.update(...) which sha-256-compares each file and downloads
 *    only what changed. Logs each file + size as it streams.
 * 7. On success, persist the new config.xml to ${APP_HOME}/config.xml. On any
 *    network/parse failure we log a warning and fall through to launch with
 *    the cached config.
 * 8. Spawn a child JVM with `java -jar ${APP_HOME}/quarkus-run.jar`. Quarkus
 *    fast-jar relies on its own classloader hierarchy and assumes it's the
 *    process entry point — running it via update4j's reflective Class.forName
 *    breaks the bootstrap. Child stdout/stderr go to ${APP_HOME}/logs/quarkus.log.
 *    Blocking — returns when Quarkus exits.
 * 9. If ${APP_HOME}/.restart-pending exists, delete it and loop back to 5.
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
     * Flipped by the service-mode shutdown hook when SCM (or WinSW) signals
     * the process to stop. Once true, the supervisor loop returns without
     * triggering crash-restart backoff.
     */
    private static final AtomicBoolean STOPPING = new AtomicBoolean(false);
    private static volatile Process currentChild;

    private Launcher() {
    }

    public static void main(String[] args) {
        boolean serviceMode = isServiceMode(args);
        Path appHome = resolveAppHome(serviceMode);
        try {
            Files.createDirectories(appHome);
        } catch (IOException e) {
            System.err.println("[launcher] cannot create " + appHome + ": " + e.getMessage());
            System.exit(1);
        }
        if (serviceMode) {
            migrateLegacyAppHome(appHome);
        }
        // Make ${app.home} resolvable inside update4j config.xml.
        System.setProperty("app.home", appHome.toString());
        configureFileLogging(appHome);

        LOG.info("[launcher] mode=" + (serviceMode ? "service" : "desktop") + " APP_HOME=" + appHome);

        if (serviceMode) {
            installServiceShutdownHook();
        }

        LauncherSplash splash = serviceMode ? null : LauncherSplash.createOrNull();
        if (splash != null) {
            splash.setStatus("Starting PoS Agent…");
            splash.setIndeterminate();
            splash.show();
        }

        try {
            superviseAndRun(appHome, splash, serviceMode);
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
     * In service mode, SIGTERM (or WinSW's stop signal) must terminate the
     * supervisor loop without triggering crash-restart backoff. We hook
     * shutdown, mark STOPPING, and forcibly destroy the Quarkus child so
     * proc.waitFor() returns immediately. The main thread is also
     * interrupted in case it's mid-backoff sleep.
     */
    private static void installServiceShutdownHook() {
        Thread main = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            STOPPING.set(true);
            LOG.info("[launcher] Service stop requested — shutting down.");
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
        }, "posagent-service-shutdown"));
    }

    private static boolean isServiceMode(String[] args) {
        for (String a : args) {
            if ("--service".equals(a)) return true;
        }
        String env = System.getenv("POSAGENT_SERVICE");
        if (env != null && (env.equals("1") || env.equalsIgnoreCase("true"))) return true;
        String prop = System.getProperty("posagent.service");
        return prop != null && (prop.equals("1") || prop.equalsIgnoreCase("true"));
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
    private static void superviseAndRun(Path appHome, LauncherSplash splash, boolean serviceMode) throws Exception {
        long[] backoffs = {1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L};
        int crashStreak = 0;

        while (true) {
            if (STOPPING.get()) {
                LOG.info("[launcher] Stop requested — exiting supervisor loop.");
                return;
            }
            long startedAt = System.currentTimeMillis();
            int exit = runOnce(appHome, splash, serviceMode);
            long uptimeMs = System.currentTimeMillis() - startedAt;

            if (STOPPING.get()) {
                LOG.info("[launcher] Stop requested — exiting after child shutdown.");
                return;
            }
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

    private static int runOnce(Path appHome, LauncherSplash splash, boolean serviceMode) throws Exception {
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
        int exit = launchQuarkus(appHome, splash, serviceMode);
        LOG.info("[launcher] Application exited with code " + exit);
        return exit;
    }

    private static int launchQuarkus(Path appHome, LauncherSplash splash, boolean serviceMode) throws IOException, InterruptedException {
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
        Thread hook = new Thread(() -> {
            if (proc.isAlive()) {
                proc.destroy();
            }
        }, "myapp-launcher-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);

        // Wait for Quarkus to be reachable on 8080 (or for it to die early). In
        // desktop mode we open the user's browser and dismiss the splash; in
        // service mode the JVM runs in Session 0 so neither makes sense — we
        // just log readiness.
        Thread waiter = new Thread(() -> {
            boolean ready = waitForPortOrExit(proc, 8080, 60_000);
            if (ready) {
                LOG.info("[launcher] Quarkus is reachable on http://localhost:8080");
                if (!serviceMode) {
                    if (splash != null) {
                        splash.setStatus("PoS Agent is ready");
                        splash.setDetail("Opening browser…");
                    }
                    openBrowser("http://localhost:8080");
                    try { Thread.sleep(900); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
            }
            if (splash != null) splash.hide();
        }, "myapp-splash-waiter");
        waiter.setDaemon(true);
        waiter.start();

        try {
            return proc.waitFor();
        } finally {
            currentChild = null;
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

    private static Path resolveAppHome(boolean serviceMode) {
        String prop = System.getProperty("app.home");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        if (serviceMode) {
            String programData = System.getenv("ProgramData");
            if (programData != null && !programData.isBlank()) {
                return Path.of(programData, "PoS Agent");
            }
            // Fallback for non-Windows test runs of service mode.
            return Path.of("/var/lib/posagent");
        }
        String appdata = System.getenv("APPDATA");
        if (appdata != null && !appdata.isBlank()) {
            return Path.of(appdata, "PoS Agent");
        }
        return Path.of(System.getProperty("user.home"), ".posagent");
    }

    /**
     * One-time migration from the desktop-mode %APPDATA% location to the
     * service-mode %PROGRAMDATA% location. Runs only when the new home is
     * empty and the legacy home has a config.xml — so reinstalls and reverts
     * are idempotent. Best-effort; failures are logged but non-fatal.
     */
    private static void migrateLegacyAppHome(Path serviceHome) {
        try {
            if (Files.exists(serviceHome.resolve("config.xml"))) return;
            String appdata = System.getenv("APPDATA");
            if (appdata == null || appdata.isBlank()) return;
            Path legacy = Path.of(appdata, "PoS Agent");
            if (!Files.isDirectory(legacy)) return;
            if (!Files.exists(legacy.resolve("config.xml"))) return;
            LOG.info("[launcher] Migrating state from " + legacy + " to " + serviceHome);
            Files.walkFileTree(legacy, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(serviceHome.resolve(legacy.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path target = serviceHome.resolve(legacy.relativize(file).toString());
                    if (!Files.exists(target)) {
                        Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[launcher] Legacy home migration failed (non-fatal): " + e.getMessage(), e);
        }
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
