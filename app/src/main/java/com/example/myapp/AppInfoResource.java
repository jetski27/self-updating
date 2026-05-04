package com.example.myapp;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;

@Path("/api/info")
public class AppInfoResource {

    public enum UpdateStatus { UP_TO_DATE, CHECKING, DOWNLOADING, RESTART_PENDING, ERROR }

    public record AppInfo(
        String version,
        String appHome,
        Instant lastUpdateCheck,
        UpdateStatus updateStatus
    ) {}

    @ConfigProperty(name = "quarkus.application.version")
    String version;

    @ConfigProperty(name = "app.home")
    String appHome;

    @Inject
    UpdateChecker updateChecker;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public AppInfo info() {
        return new AppInfo(version, appHome, updateChecker.getLastCheck(), updateChecker.getStatus());
    }
}
