package de.uni_marburg.schematch.utils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;
class ManagedProfilesTest {
    private static String sha(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }
    @Test void completeEmptyIsValidAndChangesAreRejected(@TempDir Path root) throws Exception {
        Path table = root.resolve("source.csv"), fd = root.resolve("FD_results.txt");
        Files.writeString(table, "a,b\nx,y\n");
        Files.writeString(fd, "");
        assertFalse(ManagedProfiles.requireFD(root, "source", table, fd));
        Files.createDirectories(root.resolve("metadata"));
        Path marker = root.resolve("metadata/profile-status.properties");
        Files.writeString(marker, "source.fd=not_profiled\n");
        assertThrows(IllegalStateException.class, () -> ManagedProfiles.requireFD(root, "source", table, fd));
        Files.writeString(marker, "source.fd=complete\nsource.table.sha256=" + sha(table)
            + "\nsource.fd.sha256=" + sha(fd) + "\n");
        assertTrue(ManagedProfiles.requireFD(root, "source", table, fd));
        Files.writeString(table, "a,b\nchanged,y\n");
        assertThrows(IllegalStateException.class, () -> ManagedProfiles.requireFD(root, "source", table, fd));
    }
}
