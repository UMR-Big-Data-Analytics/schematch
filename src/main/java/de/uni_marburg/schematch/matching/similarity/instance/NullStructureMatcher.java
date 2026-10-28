package de.uni_marburg.schematch.matching.similarity.instance;

import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matching.TablePairMatcher;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** X101 N null/co-missingness role term. Experimental: availability does not imply an accuracy improvement. */
@Data
@EqualsAndHashCode(callSuper = true)
public final class NullStructureMatcher extends TablePairMatcher {
    private static final Set<String> NULLS = Set.of("", "null", "none", "nan", "na", "n/a", "-");
    private int maxRows = 100000;
    private double threshold = 0.5;

    double[][] features(Table table) {
        if (maxRows < 1 || !(threshold > 0 && threshold <= 1)) throw new IllegalArgumentException("Invalid null-role parameters");
        int k = table.getNumColumns(), n = k == 0 ? 0 : Math.min(maxRows, table.getColumn(0).getValues().size());
        boolean[][] missing = new boolean[k][n];
        int[] count = new int[k];
        double[][] features = new double[k][6];
        List<Integer> partial = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            for (int r = 0; r < n; r++) {
                String value = table.getColumn(i).getValues().get(r);
                missing[i][r] = value == null || NULLS.contains(value.strip().toLowerCase(Locale.ROOT));
                if (missing[i][r]) count[i]++;
            }
            features[i][0] = n == 0 ? 0 : (double) count[i] / n;
            features[i][1] = features[i][0] == 0 ? 1 : 0;
            features[i][2] = features[i][0] == 1 ? 1 : 0;
            if (count[i] > 0 && count[i] < n) partial.add(i);
        }
        boolean[][] adjacent = new boolean[k][k];
        for (int a : partial) for (int b : partial) if (a != b) {
            int intersection = 0;
            for (int r = 0; r < n; r++) if (missing[a][r] && missing[b][r]) intersection++;
            double jaccard = (double) intersection / (count[a] + count[b] - intersection);
            adjacent[a][b] = jaccard >= threshold;
            features[a][4] = Math.max(features[a][4], jaccard);
            if (adjacent[a][b]) features[a][3] += 1.0 / (partial.size() - 1);
        }
        boolean[] visited = new boolean[k];
        for (int start : partial) if (!visited[start]) {
            List<Integer> component = new ArrayList<>();
            component.add(start);
            visited[start] = true;
            for (int q = 0; q < component.size(); q++) {
                int a = component.get(q);
                for (int b : partial) if (adjacent[a][b] && !visited[b]) {
                    visited[b] = true;
                    component.add(b);
                }
            }
            // The Python reference leaves comp zero when no column is partially missing.
            for (int member : component) features[member][5] = (double) component.size() / k;
        }
        return features;
    }

    @Override
    public float[][] match(TablePair pair) { return ValueShapeRoleMatcher.compare(features(pair.getSourceTable()), features(pair.getTargetTable())); }
}
