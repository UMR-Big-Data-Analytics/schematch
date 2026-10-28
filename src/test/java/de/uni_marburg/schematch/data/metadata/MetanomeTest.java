package de.uni_marburg.schematch.data.metadata;

import de.uni_marburg.schematch.utils.MetadataUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

public class MetanomeTest {
    @TempDir Path root;
    Path basePath;
    String fileName = "authors";
    Path testFilePath;
    @BeforeEach void prepare() throws Exception {
        basePath = Files.createDirectories(root.resolve("source"));
        testFilePath = basePath.resolve(fileName + ".csv");
        Files.writeString(testFilePath,"id,name\n1,Alice\n");
        Files.createDirectories(root.resolve("metadata/source/authors"));
    }
    @Test
    void getMetadataPath_includeFileName() {
        Path result = MetadataUtils.getMetadataPath(testFilePath, true);

        assertNotNull(result);
        assertEquals(basePath.getParent().resolve("metadata").resolve("source").resolve(fileName), result);
        assertTrue(Files.isDirectory(result.getParent()));
    }

    @Test
    void getMetadataPath_excludeFileName() {
        Path result = MetadataUtils.getMetadataPath(testFilePath, false);

        assertNotNull(result);
        assertEquals(basePath.getParent().resolve("metadata").resolve("source"), result);
        assertTrue(Files.isDirectory(result));
    }
}
