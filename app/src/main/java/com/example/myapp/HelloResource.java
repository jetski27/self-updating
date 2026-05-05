package com.example.myapp;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/api/hello")
public class HelloResource {

    @ConfigProperty(name = "quarkus.application.version")
    String version;

    public record HelloResponse(String message, String version) {}

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public HelloResponse hello() {
        return new HelloResponse("Hello from PoS Agent", version);
    }
}
