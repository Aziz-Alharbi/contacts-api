package com.unifonic.events.container;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

/** Small JRE-only entry points; never print credential values or response bodies. */
public final class ContainerSupport {
    private ContainerSupport() {}

    public static void main(String[] args) {
        try {
            switch (args[0]) {
                case "init" -> initialize(Path.of(args[1]));
                case "health" -> {
                    try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
                        var request = HttpRequest.newBuilder(URI.create(args[1])).timeout(Duration.ofSeconds(6)).GET().build();
                        if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() != 200)
                            throw new IllegalStateException("Service is not ready");
                    }
                }
                default -> throw new IllegalArgumentException("Unknown container command");
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("Container check failed: " + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    public static void initialize(Path directory) throws Exception {
        Files.createDirectories(directory);
        var random = new SecureRandom();
        for (String name : List.of("mysql-root", "mysql-app", "keycloak-admin", "demo-user", "demo-admin", "reporter", "rabbitmq")) {
            Path file = directory.resolve(name);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) < 32)
                    throw new IllegalStateException("Invalid existing development credential file");
                continue;
            }
            byte[] bytes = new byte[32]; random.nextBytes(bytes);
            Files.writeString(file, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
        }
        System.out.println("Local development credentials ready; existing values preserved.");
    }
}
