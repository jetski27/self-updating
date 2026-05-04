package com.example.myapp;

import io.quarkus.runtime.Quarkus;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

@Path("/api/updates")
public class UpdateResource {

    @Inject
    UpdateEventBus bus;

    @Inject
    UpdateChecker checker;

    @ConfigProperty(name = "app.home")
    String appHome;

    /**
     * Server-Sent Events stream of update lifecycle events.
     * Each event is emitted as `type:payload` (e.g. `downloading:app/myapp.jar:245760`,
     * `restart-pending:1.2.3`, `up-to-date:1.2.3`). Mutiny's BroadcastProcessor handles
     * disconnects automatically — when the client subscription ends the downstream is
     * torn down and no resources leak.
     */
    @GET
    @Path("/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    public Multi<String> events() {
        return bus.stream();
    }

    @POST
    @Path("/restart")
    public Response restart() throws IOException {
        java.nio.file.Path marker = Paths.get(appHome, ".restart-pending");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, checker.getLatestKnownVersion() == null ? "" : checker.getLatestKnownVersion());
        // Schedule async exit so the response can flush before the JVM stops.
        new Thread(() -> {
            try { Thread.sleep(150); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            Quarkus.asyncExit(0);
        }, "myapp-restart").start();
        return Response.accepted().build();
    }

    @POST
    @Path("/check")
    public Response checkNow() {
        checker.runCheck();
        return Response.accepted().build();
    }
}
