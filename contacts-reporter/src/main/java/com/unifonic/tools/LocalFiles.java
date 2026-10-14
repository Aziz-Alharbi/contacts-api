package com.unifonic.tools;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;

final class LocalFiles {
    private LocalFiles() {}

    static Map<String, String> environment(Path root) throws IOException {
        return environment(root, System.getenv());
    }

    static Map<String, String> environment(Path root, Map<String, String> overrides) throws IOException {
        var values = new HashMap<String, String>();
        Path file = root.resolve(".env");
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                int separator = trimmed.indexOf('=');
                if (!trimmed.startsWith("#") && separator > 0) {
                    String value = trimmed.substring(separator + 1).trim();
                    if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'")))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    values.put(trimmed.substring(0, separator).trim(), value);
                }
            }
        }
        values.putAll(overrides);
        return values;
    }

    static String randomValue() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static void privateFile(Path file, String content) throws IOException {
        // Atomic CREATE_NEW rejects existing files and symlinks. Set permissions before writing.
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(file, content, StandardOpenOption.WRITE);
    }

    static void init(Path root) throws IOException {
        var lines = new StringBuilder("KC_BOOTSTRAP_ADMIN_USERNAME=bootstrap-admin\n");
        for (String name : List.of("KC_BOOTSTRAP_ADMIN_PASSWORD", "CONTACTS_USER_PASSWORD",
                "CONTACTS_ADMIN_PASSWORD", "CONTACTS_REPORTER_SECRET")) {
            lines.append(name).append('=').append(randomValue()).append('\n');
        }
        privateFile(root.resolve(".env"), lines.toString());
    }
}
