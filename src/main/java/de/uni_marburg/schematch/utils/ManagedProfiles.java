package de.uni_marburg.schematch.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

/** Guard only generated smx exports. Legacy datasets retain their old behavior. */
public final class ManagedProfiles {
    private ManagedProfiles() {}
    public static boolean requireFD(Path scenario, String side, Path table, Path fd) throws IOException {
        Path marker = scenario.resolve("metadata/profile-status.properties");
        if (!Files.exists(marker)) return false;
        Properties props = new Properties();
        try (InputStream stream = Files.newInputStream(marker)) { props.load(stream); }
        String status = props.getProperty(side + ".fd", "not_profiled");
        if (!status.equals("complete")) {
            throw new IllegalStateException("Explicit FD profile is " + status + " for " + table
                    + "; provide a complete profile with matching checksums (see DATASETS.md); domain catalogues are not applicable");
        }
        verify(table, props.getProperty(side + ".table.sha256"));
        verify(fd, props.getProperty(side + ".fd.sha256"));
        return true;
    }
    private static void verify(Path path, String expected) throws IOException {
        if (expected == null) throw new IllegalStateException("Missing managed checksum for " + path);
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (InputStream stream = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = stream.read(buffer)) >= 0) hash.update(buffer, 0, n);
            }
            if (!HexFormat.of().formatHex(hash.digest()).equals(expected))
                throw new IllegalStateException("Managed artifact changed: " + path);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
