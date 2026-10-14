package com.unifonic.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LocalFilesTest {
    @TempDir Path directory;
    @Test void initializationCreatesUniquePrivateCredentials() throws Exception {
        LocalFiles.init(directory);
        var values = LocalFiles.environment(directory, Map.of());
        assertEquals(5, values.size()); assertEquals("bootstrap-admin", values.get("KC_BOOTSTRAP_ADMIN_USERNAME"));
        assertNotEquals(values.get("CONTACTS_USER_PASSWORD"), values.get("CONTACTS_ADMIN_PASSWORD"));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve(".env")));
    }
    @Test void initializationNeverOverwritesExistingEnvironmentFile() throws Exception {
        Files.writeString(directory.resolve(".env"), "KEEP=true\n");
        assertThrows(FileAlreadyExistsException.class, () -> LocalFiles.init(directory));
        assertEquals("KEEP=true\n", Files.readString(directory.resolve(".env")));
    }
    @Test void environmentOverridesLocalFileAndReadsQuotedValues() throws Exception {
        Files.writeString(directory.resolve(".env"), "# comment\nKEY='local'\nOTHER=\"value\"\n");
        assertEquals(Map.of("KEY", "override", "OTHER", "value"), LocalFiles.environment(directory, Map.of("KEY", "override")));
    }
    @Test void savedTokenFileIsPrivateAndContainsOnlyToken() throws Exception {
        Path output = directory.resolve("access.jwt"); LocalFiles.privateFile(output, "fixture-token");
        assertEquals("fixture-token", Files.readString(output)); assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(output));
    }
    @Test void savingNeverOverwritesExistingFile() throws Exception {
        Path output = directory.resolve("access.jwt"); Files.writeString(output, "original");
        assertThrows(FileAlreadyExistsException.class, () -> LocalFiles.privateFile(output, "fixture-token")); assertEquals("original", Files.readString(output));
    }
    @Test void savingRefusesSymbolicLink() throws Exception {
        Path output = directory.resolve("access.jwt"); Path original = directory.resolve("original"); Files.writeString(original, "keep"); Files.createSymbolicLink(output, original);
        assertThrows(FileAlreadyExistsException.class, () -> LocalFiles.privateFile(output, "fixture-token")); assertEquals("keep", Files.readString(original));
    }
    @Test void invalidCommandReturnsNonzeroStatus() { assertEquals(1, new ToolsCommand().run("unknown", "--root", directory.toString())); }
}
