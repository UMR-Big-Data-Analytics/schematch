package de.uni_marburg.schematch.utils;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InputReaderLabelsTest {
    private Table table() throws Exception {
        List<String> labels = List.of("Program (marker)", "Amount (project-level).value");
        List<Column> columns = labels.stream().map(s -> new Column(s, List.of("x", "y"))).toList();
        try {
            return Table.class.getConstructor(String.class, List.class, List.class, String.class)
                    .newInstance("source", labels, columns, "source.csv");
        } catch (NoSuchMethodException sf) {
            return Table.class.getConstructor(String.class, List.class, List.class, String.class,
                    Class.forName("de.uni_marburg.schematch.data.Database"))
                    .newInstance("source", labels, columns, "source.csv", null);
        }
    }

    @Test void parenthesesAreLabelsNotAnnotations(@TempDir Path root) throws Exception {
        Table table = table();
        Path file = root.resolve("FD_results.txt");
        String edge = "[source.csv.Program (marker)] --> source.csv.Amount (project-level).value";
        for (String annotation : List.of("", " (pdep 0.95000000000000000, 0.12500000000000000)")) {
            Files.writeString(file, edge + annotation + "\n");
            var fds = InputReader.readFDFile(file, table, new HashMap<>());
            assertEquals(1, fds.size());
            var fd = fds.iterator().next();
            assertEquals("Amount (project-level).value", fd.getDependant().getLabel());
            assertEquals("Program (marker)", fd.getDeterminant().iterator().next().getLabel());
        }
    }

    @Test void uccAndIndPreserveParentheses(@TempDir Path root) throws Exception {
        Table table = table();
        Path ucc = root.resolve("UCC_results.txt"), ind = root.resolve("inds.txt");
        Files.writeString(ucc, "[source.csv.Program (marker)]\n");
        assertEquals(1, InputReader.readUCCFile(ucc, table, new HashMap<>()).size());
        Files.writeString(ind, "[source.csv.Amount (project-level).value] [= [source.csv.Program (marker)]\n");
        assertEquals(1, InputReader.readINDFile(ind, List.of(table), List.of(table), new HashMap<>()).size());
    }
}
