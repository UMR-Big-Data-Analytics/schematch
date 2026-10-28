package de.uni_marburg.schematch.utils;

import de.uni_marburg.schematch.data.Database;
import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.data.Table;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Reads legacy table-pair truth or a full scenario matrix, aligning labeled matrices to actual table/column order. */
public final class GroundTruthReader {
    private GroundTruthReader() {}
    public record ColumnRef(String table, String column) { public String fullName() { return table + "." + column; } }

    public static List<ColumnRef> columns(Database database) {
        List<ColumnRef> columns = new ArrayList<>();
        for (Table table : database.getTables()) for (String label : table.getLabels()) columns.add(new ColumnRef(table.getName(), label));
        return columns;
    }

    public static int[][] read(Scenario scenario) {
        Configuration config = Configuration.getInstance();
        return read(Path.of(scenario.getPath()), columns(scenario.getSourceDatabase()), columns(scenario.getTargetDatabase()),
                config.getDefaultGroundTruthDir(), config.getDefaultTablePairSeparator(), config.getDefaultSeparator());
    }

    public static int[][] read(Path scenario, List<ColumnRef> source, List<ColumnRef> target,
                               String truthDir, String pairSeparator, String separator) {
        Path full = scenario.resolve("ground_truth_full.csv");
        boolean hasFull = Files.isRegularFile(full), found = hasFull;
        int[][] result = hasFull ? readMatrix(full, source, target, separator) : new int[source.size()][target.size()];
        Map<String,List<Integer>> sources = byTable(source), targets = byTable(target);
        Set<String> expected = new HashSet<>();
        for (var s : sources.entrySet()) for (var t : targets.entrySet()) {
            String filename = s.getKey() + pairSeparator + t.getKey() + ".csv";
            expected.add(filename);
            Path file = scenario.resolve(truthDir).resolve(filename);
            if (!Files.isRegularFile(file)) continue; // Absent blocks are negatives when other truth is explicitly supplied.
            found = true;
            List<ColumnRef> sr = s.getValue().stream().map(source::get).toList();
            List<ColumnRef> tc = t.getValue().stream().map(target::get).toList();
            int[][] block = readMatrix(file, sr, tc, separator);
            for (int i = 0; i < sr.size(); i++) for (int j = 0; j < tc.size(); j++) {
                int row = s.getValue().get(i), col = t.getValue().get(j);
                if (hasFull && result[row][col] != block[i][j])
                    throw new IllegalArgumentException("Full and table-pair ground truth disagree at " + source.get(row).fullName()
                            + " -> " + target.get(col).fullName() + ": " + file);
                result[row][col] = block[i][j];
            }
        }
        Path directory = scenario.resolve(truthDir);
        if (Files.isDirectory(directory)) {
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv")).toList())
                    if (!expected.contains(file.getFileName().toString()))
                        throw new IllegalArgumentException("Ground-truth file does not identify an input table pair: " + file);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        if (!found) throw new IllegalArgumentException("No ground truth for evaluation: " + scenario
                + "; inference is supported with evaluation disabled");
        return result;
    }

    private static Map<String,List<Integer>> byTable(List<ColumnRef> columns) {
        Map<String,List<Integer>> out = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) out.computeIfAbsent(columns.get(i).table(), key -> new ArrayList<>()).add(i);
        return out;
    }

    private static List<List<String>> records(Path file, String separator) {
        CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(separator).setIgnoreEmptyLines(false).build();
        try (Reader reader = CsvTables.reader(file); CSVParser parser = new CSVParser(reader, format)) {
            List<List<String>> rows = new ArrayList<>();
            for (CSVRecord record : parser) rows.add(record.toList());
            if (rows.isEmpty()) throw new IllegalArgumentException("Empty ground truth: " + file);
            return rows;
        } catch (IOException | UncheckedIOException e) {
            throw new IllegalArgumentException("Cannot read ground truth " + file + ": " + e.getMessage(), e);
        }
    }

    private static boolean isBinary(String value) {
        try { double v = Double.parseDouble(value.strip()); return v == 0 || v == 1; }
        catch (NumberFormatException e) { return false; }
    }

    public static int[][] readUnlabeled(Path file, String separator) {
        List<List<String>> rows = records(file, separator);
        int width = rows.get(0).size();
        int[][] out = new int[rows.size()][width];
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).size() != width) throw new IllegalArgumentException("Ragged ground truth: " + file);
            for (int j = 0; j < width; j++) {
                String cell = rows.get(i).get(j);
                if (!isBinary(cell)) throw new IllegalArgumentException("Expected binary ground-truth value, got '" + cell + "': " + file);
                out[i][j] = (int) Double.parseDouble(cell.strip());
            }
        }
        return out;
    }

    public static int[][] readMatrix(Path file, List<ColumnRef> source, List<ColumnRef> target, String separator) {
        List<List<String>> rows = records(file, separator);
        boolean labeled = !rows.get(0).stream().allMatch(GroundTruthReader::isBinary);
        if (!labeled) {
            if (rows.size() != source.size() || rows.stream().anyMatch(row -> row.size() != target.size()))
                throw new IllegalArgumentException("Ground-truth dimensions do not match " + source.size() + "x" + target.size() + ": " + file);
            int[][] out = new int[source.size()][target.size()];
            for (int i = 0; i < source.size(); i++) for (int j = 0; j < target.size(); j++) {
                String cell = rows.get(i).get(j);
                if (!isBinary(cell)) throw new IllegalArgumentException("Non-binary ground truth: " + file);
                out[i][j] = (int) Double.parseDouble(cell.strip());
            }
            return out;
        }
        if (rows.size() != source.size() + 1 || rows.stream().anyMatch(row -> row.size() != target.size() + 1))
            throw new IllegalArgumentException("Labeled ground-truth dimensions do not match " + source.size() + "x" + target.size() + ": " + file);
        int[] ci = align(rows.get(0).subList(1, rows.get(0).size()), target, file);
        int[] ri = align(rows.subList(1, rows.size()).stream().map(row -> row.get(0)).toList(), source, file);
        int[][] out = new int[source.size()][target.size()];
        for (int i = 0; i < source.size(); i++) for (int j = 0; j < target.size(); j++) {
            String cell = rows.get(i+1).get(j+1);
            if (!isBinary(cell)) throw new IllegalArgumentException("Non-binary ground truth: " + file);
            out[ri[i]][ci[j]] = (int) Double.parseDouble(cell.strip());
        }
        return out;
    }

    private static int[] align(List<String> labels, List<ColumnRef> expected, Path file) {
        Map<String,Integer> aliases = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        boolean single = expected.stream().map(ColumnRef::table).distinct().count() == 1;
        for (int i = 0; i < expected.size(); i++) {
            ColumnRef ref = expected.get(i);
            List<String> names = new ArrayList<>(List.of(ref.fullName(), ref.table()+".csv."+ref.column()));
            if (single) names.add(ref.column());
            for (String name : names) {
                Integer before = aliases.putIfAbsent(name, i);
                if (before != null && before != i) ambiguous.add(name);
            }
        }
        Set<Integer> seen = new HashSet<>();
        int[] indices = new int[labels.size()];
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            Integer index = aliases.get(label);
            if (index == null || ambiguous.contains(label) || !seen.add(index))
                throw new IllegalArgumentException("Unknown, ambiguous, or duplicate ground-truth label '" + label + "': " + file);
            indices[i] = index;
        }
        return indices;
    }
}
