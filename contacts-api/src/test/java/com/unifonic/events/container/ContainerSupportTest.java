package com.unifonic.events.container;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContainerSupportTest {
    @TempDir Path directory;

    @Test void generatesDistinctCredentialsAndPreservesThemAcrossRestarts() throws Exception {
        ContainerSupport.initialize(directory);
        var values = new HashMap<String, String>();
        try (var files = Files.list(directory)) {
            for (var file : files.toList()) {
                String value = Files.readString(file);
                assertTrue(value.matches("[A-Za-z0-9_-]{43}"));
                assertFalse(Files.getPosixFilePermissions(file).contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE));
                values.put(file.getFileName().toString(), value);
            }
        }
        assertEquals(7, values.size());
        assertEquals(7, new HashSet<>(values.values()).size());
        ContainerSupport.initialize(directory);
        for (var entry : values.entrySet()) assertEquals(entry.getValue(), Files.readString(directory.resolve(entry.getKey())));
    }

    @Test void refusesInvalidExistingSecretsWithoutOverwritingThem() throws Exception {
        Path existing = directory.resolve("mysql-root");
        Files.writeString(existing, "invalid");
        assertThrows(IllegalStateException.class, () -> ContainerSupport.initialize(directory));
        assertEquals("invalid", Files.readString(existing));
    }

    @Test void refusesSymlinkedSecrets() throws Exception {
        Path actual = directory.resolve("other-file");
        Files.writeString(actual, "a".repeat(43));
        Files.createSymbolicLink(directory.resolve("mysql-root"), actual);
        assertThrows(IllegalStateException.class, () -> ContainerSupport.initialize(directory));
        assertEquals("a".repeat(43), Files.readString(actual));
    }
}
