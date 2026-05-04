package com.example.myapp.launcher;

/*
 * Launcher lifecycle
 * ------------------
 * 1. Resolve APP_HOME from -Dapp.home (default ${user.home}/.myapp).
 * 2. Resolve ${APP_HOME}/config.xml — the locally cached update4j config from the
 *    last successful update. On a fresh install jpackage has placed the initial
 *    config.xml at this location for us.
 * 3. Try to discover the latest GitHub release via the public GitHub API
 *    (https://api.github.com/repos/{owner}/{repo}/releases/latest), build the URL
 *    https://github.com/{owner}/{repo}/releases/download/{tag}/config.xml, and read
 *    that remote update4j config.
 * 4. Run config.update(...) which compares SHA-256 of every file and downloads
 *    only what changed. Logs each file + size as it streams.
 * 5. On success, write the new remote config.xml to ${APP_HOME}/config.xml so
 *    next launch starts from the latest known-good state. On any network/parse
 *    failure we log a warning and fall through to launch with the cached config.
 * 6. Call config.launch() which is BLOCKING — it loads the dynamic classpath
 *    and runs Quarkus in-process.
 * 7. When the launched app exits, check ${APP_HOME}/.restart-pending. If present
 *    we delete the marker and loop back to step 3 (re-checking for updates and
 *    relaunching). If absent we exit normally.
 */

import org.update4j.Configuration;
import org.update4j.service.UpdateHandler;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;
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
        try {
            do {
                runOnce();
            } while (consumeRestartFlag());
        } catch (Throwable t) {
            System.err.println("[launcher] FATAL: " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void runOnce() throws Exception {
        Path appHome = resolveAppHome();
        Files.createDirectories(appHome);
        Path localConfig = appHome.resolve("config.xml");

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

            // Cache the new config locally so subsequent offline launches still work.
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
        return Path.of(System.getProperty("user.home"), ".myapp");
    }

    private static boolean consumeRestartFlag() throws IOException {
        Path marker = resolveAppHome().resolve(".restart-pending");
        if (Files.exists(marker)) {
            Files.deleteIfExists(marker);
            LOG.info("[launcher] Restart pending — relaunching.");
            return true;
        }
        return false;
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
