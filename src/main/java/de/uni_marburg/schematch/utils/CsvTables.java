package de.uni_marburg.schematch.utils;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Database;
import de.uni_marburg.schematch.data.Table;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Shared UTF-8/BOM-aware, string-preserving parser for normal loading and streaming dataset audits. */
public final class CsvTables {
    private CsvTables() {}
    public record Inspection(String table, List<String> labels, long rows) {}
    private record Parsed(List<String> labels, List<List<String>> values, long rows) {}

    public static BufferedReader reader(Path path) throws IOException {
        BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        reader.mark(1);
        if (reader.read() != '\ufeff') reader.reset();
        return reader;
    }

    public static Table read(Path path, String separator, Database database) {
        Parsed parsed = parse(path, separator, true);
        List<Column> columns = new ArrayList<>();
        for (int j = 0; j < parsed.labels().size(); j++) columns.add(new Column(parsed.labels().get(j), parsed.values().get(j)));
        return new Table(StringUtils.getFileName(path.toFile()), parsed.labels(), columns, path.toString(), database);
    }

    public static Inspection inspect(Path path, String separator) {
        Parsed parsed = parse(path, separator, false);
        return new Inspection(StringUtils.getFileName(path.toFile()), parsed.labels(), parsed.rows());
    }

    private static Parsed parse(Path path, String separator, boolean keepValues) {
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setDelimiter(separator)
                .setIgnoreEmptyLines(false).setAllowMissingColumnNames(true).build();
        try (Reader reader = reader(path); CSVParser parser = new CSVParser(reader, format)) {
            List<String> labels = List.copyOf(parser.getHeaderNames());
            if (labels.isEmpty()) throw new IllegalArgumentException("CSV has no header: " + path);
            if (new HashSet<>(labels).size() != labels.size())
                throw new IllegalArgumentException("Duplicate CSV headers require explicit import/renaming: " + path);
            List<List<String>> values = new ArrayList<>();
            if (keepValues) for (int j = 0; j < labels.size(); j++) values.add(new ArrayList<>());
            long rows = 0;
            for (CSVRecord record : parser) {
                if (record.size() != labels.size()) throw new IllegalArgumentException("CSV row " + record.getRecordNumber()
                        + " has " + record.size() + " cells; expected " + labels.size() + ": " + path);
                if (keepValues) for (int j = 0; j < labels.size(); j++) values.get(j).add(record.get(j));
                rows++;
            }
            return new Parsed(labels, values, rows);
        } catch (IOException | UncheckedIOException e) {
            throw new IllegalArgumentException("Cannot parse CSV " + path + ": " + e.getMessage(), e);
        }
    }
}
