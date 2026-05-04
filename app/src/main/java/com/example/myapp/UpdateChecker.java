package com.example.myapp;

import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.myapp.AppInfoResource.UpdateStatus;

@ApplicationScoped
public class UpdateChecker {

    private static final Logger LOG = Logger.getLogger(UpdateChecker.class.getName());
    private static final Pattern TAG_PATTERN = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    @ConfigProperty(name = "quarkus.application.version")
    String currentVersion;

    @ConfigProperty(name = "app.github.owner")
    String owner;

    @ConfigProperty(name = "app.github.repo")
    String repo;

    @Inject
    UpdateEventBus bus;

    private volatile Instant lastCheck = Instant.EPOCH;
    private volatile UpdateStatus status = UpdateStatus.UP_TO_DATE;
    private volatile String latestKnownVersion;

    private HttpClient http;

    @PostConstruct
    void init() {
        this.http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.latestKnownVersion = currentVersion;
    }

    /**
     * Polls GitHub Releases. Informational only — actual binary updates happen
     * in the launcher on next start. We just notify the UI when a new tag exists
     * so it can prompt the user to restart.
     */
    @Scheduled(every = "1h", delayed = "30s")
    public void check() {
        runCheck();
    }

    public synchronized void runCheck() {
        status = UpdateStatus.CHECKING;
        bus.emit("checking:" + currentVersion);
        try {
            URI api = URI.create("https://api.github.com/repos/" + owner + "/" + repo + "/releases/latest");
            HttpRequest req = HttpRequest.newBuilder(api)
                .timeout(READ_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "myapp")
                .GET()
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            lastCheck = Instant.now();

            if (resp.statusCode() / 100 != 2) {
                status = UpdateStatus.ERROR;
                bus.emit("error:HTTP " + resp.statusCode());
                return;
            }
            Matcher m = TAG_PATTERN.matcher(resp.body());
            if (!m.find()) {
                status = UpdateStatus.ERROR;
                bus.emit("error:tag_name missing");
                return;
            }
            String tag = m.group(1);
            String remote = tag.startsWith("v") ? tag.substring(1) : tag;
            latestKnownVersion = remote;

            if (remote.equals(currentVersion)) {
                status = UpdateStatus.UP_TO_DATE;
                bus.emit("up-to-date:" + currentVersion);
            } else {
                status = UpdateStatus.RESTART_PENDING;
                bus.emit("restart-pending:" + remote);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.log(Level.WARNING, "Update check failed: " + e.getMessage(), e);
            status = UpdateStatus.ERROR;
            bus.emit("error:" + e.getClass().getSimpleName());
        }
    }

    public Instant getLastCheck() {
        return lastCheck;
    }

    public UpdateStatus getStatus() {
        return status;
    }

    public String getLatestKnownVersion() {
        return latestKnownVersion;
    }
}
