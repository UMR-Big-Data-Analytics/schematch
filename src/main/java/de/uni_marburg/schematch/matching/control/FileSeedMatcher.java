package de.uni_marburg.schematch.matching.control;

import de.uni_marburg.schematch.data.Database;
import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.matching.Matcher;
import de.uni_marburg.schematch.matchtask.MatchTask;
import de.uni_marburg.schematch.matchtask.matchstep.MatchingStep;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays a stored score matrix as a matcher: reads {@code <seedRoot>/<seed>/<dataset>/<scenario>/seed.csv} (the study's
 * seed format: header {@code Source\Target,<target labels>}, one row per source label) and returns it in Schematch's column
 * order, aligned by {@code table.column} label. Missing or empty cells become NaN.
 *
 * <p>A control for regression checks (tools/check_composite_regression.py): composites and refinements can then be run on
 * exactly the component matrices the study used, without the Python servers or models that produced them.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FileSeedMatcher extends Matcher {
    private String seedRoot = "";
    private String seed = "";

    @Override
    public float[][] match(MatchTask matchTask, MatchingStep matchStep) {
        return read(matchTask.getScenario());
    }

    public float[][] read(Scenario scenario) {
        Path scen = Path.of(scenario.getPath());
        Path file = Path.of(seedRoot, seed, scen.getParent().getFileName().toString(), scen.getFileName().toString(), "seed.csv");
        Database src = scenario.getSourceDatabase();
        Database tgt = scenario.getTargetDatabase();
        Map<String, Integer> ri = index(src);
        Map<String, Integer> ci = index(tgt);
        float[][] out = new float[src.getNumColumns()][tgt.getNumColumns()];
        for (float[] row : out) {
            java.util.Arrays.fill(row, Float.NaN);
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT)) {
            List<CSVRecord> records = parser.getRecords();
            if (records.isEmpty()) {
                throw new IllegalStateException("Empty seed file " + file);
            }
            CSVRecord header = records.get(0);
            int[] col = new int[header.size()];
            for (int k = 1; k < header.size(); k++) {
                Integer j = ci.get(header.get(k));
                if (j == null) {
                    throw new IllegalStateException("Target label " + header.get(k) + " of " + file + " not in the scenario");
                }
                col[k] = j;
            }
            for (int r = 1; r < records.size(); r++) {
                CSVRecord rec = records.get(r);
                if (rec.size() == 0 || rec.get(0).isEmpty()) {
                    continue;
                }
                Integer i = ri.get(rec.get(0));
                if (i == null) {
                    throw new IllegalStateException("Source label " + rec.get(0) + " of " + file + " not in the scenario");
                }
                for (int k = 1; k < rec.size() && k < col.length; k++) {
                    String v = rec.get(k).trim();
                    out[i][col[k]] = v.isEmpty() || v.equalsIgnoreCase("nan") ? Float.NaN : Float.parseFloat(v);
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read seed " + file, e);
        }
        return out;
    }

    private static Map<String, Integer> index(Database db) {
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < db.getNumColumns(); i++) {
            out.put(db.getFullColumnNameByIndex(i), i);
        }
        return out;
    }
}
