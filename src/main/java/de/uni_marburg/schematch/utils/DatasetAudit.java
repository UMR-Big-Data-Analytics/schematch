package de.uni_marburg.schematch.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.uni_marburg.schematch.data.Dataset;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Full streaming CSV/GT audit through the normal Java parsers; never profiles or rewrites datasets. */
public final class DatasetAudit {
    private static final ObjectMapper JSON = new ObjectMapper();
    private DatasetAudit() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("DatasetAudit <datasets-manifest.json> <report.json>");
        Configuration config = Configuration.getInstance();
        config.setReadDependencies(false);
        Configurator.setRootLevel(Level.WARN);
        Map<String,CsvTables.Inspection> cache = new HashMap<>();
        List<Map<String,Object>> rows = new ArrayList<>();
        int errors = 0, datasets = 0;
        for (JsonNode entry : JSON.readTree(Path.of(args[0]).toFile())) {
            datasets++;
            String name = entry.path("name").asText(), origin = entry.path("origin").asText();
            Path path = Path.of(entry.path("path").asText());
            try {
                Dataset dataset = new Dataset(new Configuration.DatasetConfiguration(name,path.toString()));
                for (String scenarioName : dataset.getScenarioNames()) {
                    Path scenario = path.resolve(scenarioName);
                    Map<String,Object> row = new LinkedHashMap<>();
                    row.put("origin",origin); row.put("dataset",name); row.put("scenario",scenarioName); row.put("path",scenario.toString());
                    try {
                        List<GroundTruthReader.ColumnRef> source = inspect(scenario.resolve(config.getDefaultSourceDatabaseDir()),cache,config,row,"source");
                        List<GroundTruthReader.ColumnRef> target = inspect(scenario.resolve(config.getDefaultTargetDatabaseDir()),cache,config,row,"target");
                        Path truth = scenario.resolve(config.getDefaultGroundTruthDir());
                        boolean hasTruth = Files.isRegularFile(scenario.resolve("ground_truth_full.csv"));
                        if (!hasTruth && Files.isDirectory(truth)) try (var files = Files.list(truth)) {
                            hasTruth = files.anyMatch(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"));
                        }
                        if (hasTruth) {
                            int[][] gt = GroundTruthReader.read(scenario,source,target,config.getDefaultGroundTruthDir(),
                                    config.getDefaultTablePairSeparator(),config.getDefaultSeparator());
                            row.put("ground_truth_pairs",ArrayUtils.sumOfMatrix(gt));
                            row.put("status","evaluable");
                        } else row.put("status","inference_only");
                        row.put("metadata_present",Files.isDirectory(scenario.resolve("metadata")));
                    } catch (Exception e) {
                        errors++; row.put("status","error"); row.put("error",e.getMessage());
                    }
                    rows.add(row);
                }
            } catch (Exception e) {
                errors++;
                rows.add(new LinkedHashMap<>(Map.of("origin",origin,"dataset",name,"path",path.toString(),"status","error","error",String.valueOf(e.getMessage()))));
            }
            if (datasets % 10 == 0) System.out.println("Audited " + datasets + " datasets; " + rows.size() + " scenarios; " + errors + " errors");
        }
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("datasets",datasets); report.put("scenarios",rows.size()); report.put("errors",errors);
        report.put("evaluable",rows.stream().filter(r -> "evaluable".equals(r.get("status"))).count());
        report.put("inference_only",rows.stream().filter(r -> "inference_only".equals(r.get("status"))).count());
        report.put("unique_table_files_parsed",cache.size()); report.put("rows",rows);
        Path out = Path.of(args[1]);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.toFile(),report);
        System.out.println("Finished: " + rows.size() + " scenarios, " + errors + " errors -> " + out);
        if (errors > 0) System.exit(2);
    }

    private static List<GroundTruthReader.ColumnRef> inspect(Path directory, Map<String,CsvTables.Inspection> cache,
                                                            Configuration config, Map<String,Object> row, String side) throws IOException {
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv")).sorted().toList();
        }
        if (files.isEmpty()) throw new IllegalArgumentException("No CSV tables in " + directory);
        List<GroundTruthReader.ColumnRef> columns = new ArrayList<>();
        long records = 0;
        for (Path file : files) {
            BasicFileAttributes attr = Files.readAttributes(file,BasicFileAttributes.class);
            String key = (attr.fileKey() == null ? file.toRealPath().toString() : attr.fileKey().toString())
                    + ":" + attr.size() + ":" + attr.lastModifiedTime();
            CsvTables.Inspection table = cache.computeIfAbsent(key, k -> CsvTables.inspect(file,config.getDefaultSeparator()));
            String name = StringUtils.getFileName(file.toFile());
            for (String label : table.labels()) columns.add(new GroundTruthReader.ColumnRef(name,label));
            records += table.rows();
        }
        row.put(side+"_tables",files.size()); row.put(side+"_columns",columns.size()); row.put(side+"_rows",records);
        return columns;
    }
}
