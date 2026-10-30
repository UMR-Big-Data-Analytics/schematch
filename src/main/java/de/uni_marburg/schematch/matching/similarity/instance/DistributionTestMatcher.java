package de.uni_marburg.schematch.matching.similarity.instance;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matching.TablePairMatcher;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Distribution-comparison matchers, as similarity {@code 1 - distance}.
 *
 * <p>{@code test}:
 * <ul>
 *     <li>KS: two-sample Kolmogorov-Smirnov statistic sup|F1 - F2|.</li>
 *     <li>WASSERSTEIN: 1-D earth mover's distance between the empirical distributions, divided by
 *     the range of the pooled values so that it lies in [0, 1] (the EMD of Zhang et al.,
 *     SIGMOD 2011, without its clustering phases; Valentine's full Distribution-based matcher is
 *     available through the Python sidecar).</li>
 *     <li>JSD: Jensen-Shannon divergence (base 2) between value frequencies (domain VALUE) or
 *     between {@code bins}-bin histograms over the pooled range (domains NUMERIC, LENGTH).</li>
 * </ul>
 * {@code domain}: AUTO (default; NUMERIC when at least 90% of the non-null cells of both columns
 * parse as numbers, otherwise LENGTH), NUMERIC (non-numeric columns score 0), LENGTH (cell string
 * lengths, defined for every column and invariant to value recoding of equal length), or VALUE
 * (JSD only). This is distinct from {@link DistributionMatcher} (the sorted-PMF seed), which
 * compares rank-frequency profiles and ignores the values themselves.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DistributionTestMatcher extends TablePairMatcher {
    private String test = "KS";
    private String domain = "AUTO";
    private int bins = 20;

    @Override
    public float[][] match(TablePair tablePair) {
        Table s = tablePair.getSourceTable();
        Table t = tablePair.getTargetTable();
        String tst = test.toUpperCase(Locale.ROOT);
        String dom = domain.toUpperCase(Locale.ROOT);
        if (!List.of("KS", "WASSERSTEIN", "JSD").contains(tst)) {
            throw new IllegalArgumentException("Unknown distribution test: " + test);
        }
        if (!List.of("AUTO", "NUMERIC", "LENGTH", "VALUE").contains(dom)) {
            throw new IllegalArgumentException("Unknown distribution domain: " + domain);
        }
        if (dom.equals("VALUE") && !tst.equals("JSD")) {
            throw new IllegalArgumentException("Domain VALUE is only defined for test JSD");
        }
        Profile[] left = new Profile[s.getNumColumns()];
        for (int i = 0; i < left.length; i++) {
            left[i] = new Profile(s.getColumn(i));
        }
        Profile[] right = new Profile[t.getNumColumns()];
        for (int j = 0; j < right.length; j++) {
            right[j] = new Profile(t.getColumn(j));
        }
        float[][] sim = tablePair.getEmptySimMatrix();
        for (int i = 0; i < left.length; i++) {
            for (int j = 0; j < right.length; j++) {
                sim[i][j] = (float) similarity(left[i], right[j], tst, dom);
            }
        }
        return sim;
    }

    static final class Profile {
        final List<String> nonNull;
        final double numericFraction;
        double[] numbers;
        double[] lengths;
        Map<String, Double> frequencies;

        Profile(Column c) {
            nonNull = ColumnValues.nonNull(c);
            numericFraction = ColumnValues.numericFraction(nonNull);
        }

        double[] numbers() {
            if (numbers == null) {
                numbers = ColumnValues.numbers(nonNull);
                Arrays.sort(numbers);
            }
            return numbers;
        }

        double[] lengths() {
            if (lengths == null) {
                lengths = ColumnValues.lengths(nonNull);
                Arrays.sort(lengths);
            }
            return lengths;
        }

        Map<String, Double> frequencies() {
            if (frequencies == null) {
                frequencies = ColumnValues.frequencies(nonNull);
            }
            return frequencies;
        }
    }

    double similarity(Profile a, Profile b, String tst, String dom) {
        if (a.nonNull.isEmpty() || b.nonNull.isEmpty()) {
            return 0.0;
        }
        if (dom.equals("VALUE")) {
            return 1.0 - ColumnValues.jensenShannon(a.frequencies(), b.frequencies());
        }
        boolean numeric = dom.equals("NUMERIC")
                || (dom.equals("AUTO") && a.numericFraction >= 0.9 && b.numericFraction >= 0.9);
        double[] x = numeric ? a.numbers() : a.lengths();
        double[] y = numeric ? b.numbers() : b.lengths();
        if (x.length == 0 || y.length == 0) {
            return 0.0;
        }
        return switch (tst) {
            case "KS" -> 1.0 - ks(x, y);
            case "WASSERSTEIN" -> 1.0 - wasserstein(x, y);
            default -> 1.0 - ColumnValues.jensenShannon(histogram(x, x, y, bins), histogram(y, x, y, bins));
        };
    }

    /** Two-sample KS statistic of sorted samples. */
    static double ks(double[] x, double[] y) {
        int i = 0, j = 0;
        double d = 0;
        while (i < x.length && j < y.length) {
            double v = Math.min(x[i], y[j]);
            while (i < x.length && x[i] <= v) {
                i++;
            }
            while (j < y.length && y[j] <= v) {
                j++;
            }
            d = Math.max(d, Math.abs((double) i / x.length - (double) j / y.length));
        }
        return d;
    }

    /** W1 between the empirical CDFs of sorted samples, divided by the pooled range. */
    static double wasserstein(double[] x, double[] y) {
        double lo = Math.min(x[0], y[0]);
        double hi = Math.max(x[x.length - 1], y[y.length - 1]);
        if (hi - lo <= 0) {
            return 0.0;
        }
        double[] all = new double[x.length + y.length];
        System.arraycopy(x, 0, all, 0, x.length);
        System.arraycopy(y, 0, all, x.length, y.length);
        Arrays.sort(all);
        int i = 0, j = 0;
        double w = 0;
        for (int k = 0; k < all.length - 1; k++) {
            double v = all[k];
            while (i < x.length && x[i] <= v) {
                i++;
            }
            while (j < y.length && y[j] <= v) {
                j++;
            }
            w += Math.abs((double) i / x.length - (double) j / y.length) * (all[k + 1] - v);
        }
        return Math.min(1.0, w / (hi - lo));
    }

    static Map<String, Double> histogram(double[] v, double[] x, double[] y, int bins) {
        double lo = Math.min(x[0], y[0]);
        double hi = Math.max(x[x.length - 1], y[y.length - 1]);
        Map<String, Double> h = new HashMap<>();
        int b = Math.max(1, bins);
        for (double d : v) {
            int k = hi > lo ? (int) Math.min(b - 1, Math.floor((d - lo) / (hi - lo) * b)) : 0;
            h.merge(Integer.toString(k), 1.0 / v.length, Double::sum);
        }
        return h;
    }
}
