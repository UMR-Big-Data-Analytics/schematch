package de.uni_marburg.schematch.matching.similarityFlooding;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Database;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matching.TablePairMatcher;
import de.uni_marburg.schematch.matching.TokenizedTablePairMatcher;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import de.uni_marburg.schematch.utils.Configuration;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Edges between the columns of one table (channel C3 of the SF study), generated natively instead of read from
 * {@code extraEdgesDir} files. Each column gets directed edges to its k nearest other columns of the same table
 * under a within-table measure, or, as the control, to k random other columns (the same out-degree).
 *
 * <p>Measures: {@code SORTED_PMF}, the L1 distance between the columns' value-frequency vectors sorted in
 * descending order (relative frequencies of the non-empty values, the shorter vector padded with zeros; label
 * {@code distsim}; sf-matcher-study layer3b/f6_edges.py), or any {@link TablePairMatcher}, run on the table paired
 * with itself and read as a similarity (label {@code sim}; servers/make_x62.py). For a similarity, a column whose
 * similarities to the other columns are constant or not finite gets no edge.
 *
 * <p>Ties: {@code POSITION} prefers the earlier column (f6_edges.py knn); {@code RANK} rounds the scores to 12
 * decimals and breaks ties by one random rank per table, numpy's default_rng(int(sha256(key)[:16], 16))
 * .permutation(n) with key "dataset/scenario/side/tiebreak" (layer3b/f6_knn2_rnd.py); {@code DRAW} draws one
 * random number per candidate and column, numpy's default_rng(crc32(key)).random(n) with key
 * "seed|dataset|scenario|side|column index" (make_x62.py, whose seed is "x62|regime|matcher"). The control
 * {@code RANDOM} draws k other columns per column with Python's random.Random(sha256(key).hexdigest()).sample,
 * key "dataset/scenario/side" (f6_edges.py rnd2). For RANK and RANDOM a non-empty seed is appended to the key.
 * With several tables per schema, "side" in the keys is "side/table".
 */
final class WithinTableEdges {
    private WithinTableEdges() {}

    enum Mode { NONE, KNN, RANDOM }

    enum TieBreak { POSITION, RANK, DRAW }

    static final String SORTED_PMF = "SORTED_PMF";

    /** The parsed matcher parameters withinTable*. */
    record Spec(Mode mode, String measure, int k, TieBreak tieBreak, String seed, String label) {
        static Spec of(String mode, String measure, String k, String tieBreak, String seed, String label) {
            Mode m = Mode.valueOf(blankOr(mode, "NONE").toUpperCase(Locale.ROOT));
            String me = blankOr(measure, SORTED_PMF);
            int kk = Integer.parseInt(blankOr(k, "2").trim());
            if (kk < 1) throw new IllegalArgumentException("withinTableK must be positive");
            TieBreak t = TieBreak.valueOf(blankOr(tieBreak, "POSITION").toUpperCase(Locale.ROOT));
            String l = blankOr(label, SORTED_PMF.equalsIgnoreCase(me) || m == Mode.RANDOM ? "distsim" : "sim");
            if (l.contains("\t")) throw new IllegalArgumentException("withinTableLabel must not contain a tab");
            Spec spec = new Spec(m, me, kk, t, seed == null ? "" : seed, l);
            if (m == Mode.KNN && !SORTED_PMF.equalsIgnoreCase(me)) spec.matcher();  // fail early on a bad class
            return spec;
        }

        boolean enabled() {
            return mode != Mode.NONE;
        }

        /** The table-pair matcher behind a matcher measure, by class name below the matching package or in full. */
        TablePairMatcher matcher() {
            String name = measure.startsWith("de.") ? measure : Configuration.MATCHING_PACKAGE_NAME + "." + measure;
            Object instance;
            try {
                instance = Class.forName(name).getConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("withinTableMeasure: no matcher class " + name, e);
            }
            if (instance instanceof TokenizedTablePairMatcher || !(instance instanceof TablePairMatcher matcher)) {
                throw new IllegalArgumentException("withinTableMeasure must be SORTED_PMF or an untokenized table-pair "
                        + "matcher, not " + name);
            }
            return matcher;
        }
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** The edges of one schema in the extra-edge line format ("label TAB table TAB from TAB to TAB 1.0"). */
    static List<String> lines(Spec spec, String dataset, String scenario, String side, Database db) {
        List<String> out = new ArrayList<>();
        if (!spec.enabled()) return out;
        boolean single = db.getTables().size() == 1;
        for (Table table : db.getTables()) {
            String sideKey = single ? side : side + "/" + table.getName();
            List<String> header = new ArrayList<>();
            for (Column column : table.getColumns()) header.add(column.getLabel());
            List<int[]> edges;
            if (spec.mode() == Mode.RANDOM) {
                edges = randomEdges(header.size(), spec.k(), dataset + "/" + scenario + "/" + sideKey + spec.seed());
            } else if (SORTED_PMF.equalsIgnoreCase(spec.measure())) {
                edges = nearest(sortedPmfDistances(table), false, spec, dataset, scenario, sideKey);
            } else {
                float[][] self = spec.matcher().match(new TablePair(table, table));
                double[][] sim = new double[self.length][];
                for (int i = 0; i < self.length; i++) {
                    sim[i] = new double[self[i].length];
                    for (int j = 0; j < self[i].length; j++) sim[i][j] = self[i][j];
                }
                edges = nearest(sim, true, spec, dataset, scenario, sideKey);
            }
            for (int[] e : edges) {
                out.add(spec.label() + "\t" + table.getName() + "\t" + header.get(e[0]) + "\t" + header.get(e[1]) + "\t1.0");
            }
        }
        return out;
    }

    /** The k nearest other columns of every column, under the spec's tie-break. */
    static List<int[]> nearest(double[][] scores, boolean similarity, Spec spec, String dataset, String scenario,
                               String sideKey) {
        return switch (spec.tieBreak()) {
            case POSITION -> knn(scores, similarity, spec.k(), null, null);
            case RANK -> knn(scores, similarity, spec.k(),
                    tieRanks(scores.length, dataset + "/" + scenario + "/" + sideKey + "/tiebreak" + spec.seed()), null);
            case DRAW -> knn(scores, similarity, spec.k(), null,
                    spec.seed() + "|" + dataset + "|" + scenario + "|" + sideKey + "|");
        };
    }

    /**
     * Each column's k best other columns. Distances ascend, similarities descend; ties by position, by a per-table
     * rank (scores rounded to 12 decimals first, as np.round), or by per-column draws (drawPrefix + column index).
     */
    static List<int[]> knn(double[][] scores, boolean similarity, int k, int[] rank, String drawPrefix) {
        int n = scores.length;
        List<int[]> edges = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double[] row = new double[n];
            for (int j = 0; j < n; j++) {
                double s = similarity ? -scores[i][j] : scores[i][j];
                row[j] = rank != null ? Math.rint(s * 1e12) / 1e12 : s;
            }
            if (similarity && !informative(scores[i], i)) continue;
            double[] draw = null;
            if (drawPrefix != null) {
                CRC32 crc = new CRC32();
                crc.update((drawPrefix + i).getBytes(StandardCharsets.UTF_8));
                StudyRandom.Numpy rng = new StudyRandom.Numpy(crc.getValue());
                draw = new double[n];
                for (int j = 0; j < n; j++) draw[j] = rng.random();
            }
            final double[] d = draw;
            List<Integer> others = new ArrayList<>();
            for (int j = 0; j < n; j++) if (j != i) others.add(j);
            Comparator<Integer> byScore = (a, b) -> row[a] < row[b] ? -1 : row[a] > row[b] ? 1 : 0;
            Comparator<Integer> tie = rank != null ? Comparator.comparingInt(j -> rank[j])
                    : d != null ? (a, b) -> d[a] < d[b] ? -1 : d[a] > d[b] ? 1 : 0
                    : Comparator.comparingInt(j -> j);
            others.sort(byScore.thenComparing(tie));
            for (int j : others.subList(0, Math.min(k, others.size()))) edges.add(new int[]{i, j});
        }
        return edges;
    }

    /** A similarity row carries a ranking: its off-diagonal values are finite and not all equal. */
    private static boolean informative(double[] row, int i) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        int count = 0;
        for (int j = 0; j < row.length; j++) {
            if (j == i) continue;
            if (!Double.isFinite(row[j])) return false;
            min = Math.min(min, row[j]);
            max = Math.max(max, row[j]);
            count++;
        }
        return count > 0 && max > min;
    }

    /** numpy default_rng(int(sha256(key).hexdigest()[:16], 16)).permutation(n). */
    static int[] tieRanks(int n, String key) {
        BigInteger seed = new BigInteger(StudyRandom.sha256Hex(key).substring(0, 16), 16);
        return new StudyRandom.Numpy(seed).permutation(n);
    }

    /** The control: k random other columns per column (Python random.sample, one generator per table). */
    static List<int[]> randomEdges(int n, int k, String key) {
        StudyRandom.Python rng = new StudyRandom.Python(StudyRandom.sha256Hex(key));
        List<int[]> edges = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<Integer> others = new ArrayList<>();
            for (int j = 0; j < n; j++) if (j != i) others.add(j);
            for (int j : rng.sample(others, Math.min(k, n - 1))) edges.add(new int[]{i, j});
        }
        return edges;
    }

    /** Sorted-PMF L1 distances between the columns of a table, summed as numpy does (pairwise). */
    static double[][] sortedPmfDistances(Table table) {
        List<Column> columns = table.getColumns();
        int n = columns.size();
        double[][] pmf = new double[n][];
        for (int c = 0; c < n; c++) pmf[c] = sortedPmf(columns.get(c).getValues());
        double[][] d = new double[n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) d[i][j] = distance(pmf[i], pmf[j]);
        return d;
    }

    static double[] sortedPmf(List<String> values) {
        Map<String, Integer> counts = new HashMap<>();
        for (String v : values) if (!v.isEmpty()) counts.merge(v, 1, Integer::sum);
        double[] v = new double[counts.size()];
        int k = 0;
        for (int c : counts.values()) v[k++] = c;
        Arrays.sort(v);
        for (int a = 0, b = v.length - 1; a < b; a++, b--) { double t = v[a]; v[a] = v[b]; v[b] = t; }
        if (v.length == 0) return v;
        double total = pairwiseSum(v, 0, v.length);
        for (int i = 0; i < v.length; i++) v[i] = v[i] / total;
        return v;
    }

    static double distance(double[] a, double[] b) {
        if (a.length == 0 && b.length == 0) return 0.0;
        int n = Math.max(a.length, b.length);
        double[] diff = new double[n];
        for (int i = 0; i < n; i++) diff[i] = Math.abs((i < a.length ? a[i] : 0.0) - (i < b.length ? b[i] : 0.0));
        return pairwiseSum(diff, 0, n);
    }

    /** numpy's pairwise summation (8 accumulators up to blocks of 128, halving above). */
    static double pairwiseSum(double[] a, int offset, int n) {
        if (n < 8) {
            double res = 0.0;
            for (int i = 0; i < n; i++) res += a[offset + i];
            return res;
        } else if (n <= 128) {
            double[] r = new double[8];
            for (int j = 0; j < 8; j++) r[j] = a[offset + j];
            int i;
            for (i = 8; i < n - (n % 8); i += 8) {
                for (int j = 0; j < 8; j++) r[j] += a[offset + i + j];
            }
            double res = ((r[0] + r[1]) + (r[2] + r[3])) + ((r[4] + r[5]) + (r[6] + r[7]));
            for (; i < n; i++) res += a[offset + i];
            return res;
        } else {
            int n2 = n / 2;
            n2 -= n2 % 8;
            return pairwiseSum(a, offset, n2) + pairwiseSum(a, offset + n2, n - n2);
        }
    }
}
