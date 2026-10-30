package de.uni_marburg.schematch.matching.control;

import de.uni_marburg.schematch.matching.TablePairMatcher;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import de.uni_marburg.schematch.utils.Configuration;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A controlled seed built from the ground truth, for seed-quality sweeps: <b>never a matcher</b>,
 * and never to be reported as one.
 *
 * <p>{@code s = (1 - noise) * g' + noise * u}, where g is the 0/1 ground truth, g' is g with a
 * fraction {@code erase} of its true pairs set to 0 (recall loss), and u is i.i.d. uniform noise.
 * noise = 0, erase = 0 is the oracle; noise = 1 is a random seed. Additionally a fraction
 * {@code decoy} of the true pairs gets a decoy: a random false pair in the same source row raised
 * to 1 (precision loss with the right column still present). All draws come from {@code seed} and
 * the table-pair name, so a configuration is reproducible.
 *
 * <p>The ground truth is read from {@code <scenario>/<defaultGroundTruthDir>/<tablePair>.csv},
 * the file the evaluator reads; a table pair without one gets the all-zero oracle.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OracleMatcher extends TablePairMatcher {
    private float noise = 0.0f;
    private float erase = 0.0f;
    private float decoy = 0.0f;
    private int seed = 0;

    @Override
    public float[][] match(TablePair tablePair) {
        float[][] sim = tablePair.getEmptySimMatrix();
        File scenario = new File(tablePair.getSourceTable().getDatabase().getPath()).getParentFile();
        String path = scenario.getPath() + File.separator + Configuration.getInstance().getDefaultGroundTruthDir()
                + File.separator + tablePair + ".csv";
        int[][] gt = readGroundTruth(path, sim.length, sim.length == 0 ? 0 : sim[0].length);
        Random rng = new Random(seed * 1_000_003L + tablePair.toString().hashCode());
        int rows = sim.length;
        int cols = rows == 0 ? 0 : sim[0].length;
        float[][] g = new float[rows][cols];
        List<int[]> truePairs = new ArrayList<>();
        if (gt != null) {
            for (int i = 0; i < rows; i++) {
                for (int j = 0; j < cols; j++) {
                    if (gt[i][j] > 0) {
                        truePairs.add(new int[]{i, j});
                    }
                }
            }
        }
        for (int[] p : truePairs) {
            if (rng.nextDouble() >= erase) {
                g[p[0]][p[1]] = 1f;
            }
        }
        for (int[] p : truePairs) {
            if (rng.nextDouble() < decoy) {
                List<Integer> falseColumns = new ArrayList<>();
                for (int j = 0; j < cols; j++) {
                    if (gt[p[0]][j] <= 0) {
                        falseColumns.add(j);
                    }
                }
                if (!falseColumns.isEmpty()) {
                    g[p[0]][falseColumns.get(rng.nextInt(falseColumns.size()))] = 1f;
                }
            }
        }
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                sim[i][j] = (1f - noise) * g[i][j] + noise * rng.nextFloat();
            }
        }
        return sim;
    }

    /**
     * The same dense 0/1 CSV that {@code InputReader.readGroundTruthFile} reads, read here with a
     * closed reader (that method leaves its second reader open, which locks the file on Windows).
     */
    static int[][] readGroundTruth(String path, int rows, int cols) {
        File file = new File(path);
        if (!file.isFile()) {
            return null;
        }
        String separator = Configuration.getInstance().getDefaultSeparator();
        int[][] gt = new int[rows][cols];
        try (BufferedReader reader = Files.newBufferedReader(file.toPath())) {
            String line;
            int i = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cells = line.split(separator);
                if (i >= rows || cells.length != cols) {
                    throw new IllegalStateException("Ground truth " + path + " does not have shape "
                            + rows + "x" + cols);
                }
                for (int j = 0; j < cols; j++) {
                    gt[i][j] = Integer.parseInt(cells[j].trim());
                }
                i++;
            }
            if (i != rows) {
                throw new IllegalStateException("Ground truth " + path + " has " + i + " rows, expected " + rows);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return gt;
    }
}
