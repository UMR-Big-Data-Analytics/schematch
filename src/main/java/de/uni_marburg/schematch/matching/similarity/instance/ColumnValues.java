package de.uni_marburg.schematch.matching.similarity.instance;

import de.uni_marburg.schematch.data.Column;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Value access shared by the instance matchers. The empty string is the null token, as in the
 * harness's CSV policy ({@code null_token: ""}); whitespace-only cells count as null too.
 */
public final class ColumnValues {
    private ColumnValues() {
    }

    public static boolean isNull(String v) {
        return v == null || v.isBlank();
    }

    public static List<String> nonNull(Column column) {
        List<String> out = new ArrayList<>();
        for (String v : column.getValues()) {
            if (!isNull(v)) {
                out.add(v);
            }
        }
        return out;
    }

    public static Set<String> distinct(Column column, boolean normalise) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : column.getValues()) {
            if (!isNull(v)) {
                out.add(normalise ? v.trim().toLowerCase(Locale.ROOT) : v);
            }
        }
        return out;
    }

    /**
     * A deterministic sample of at most {@code cap} distinct values: sorted, then shuffled with a
     * fixed seed, so the sample does not depend on row order. {@code cap <= 0} keeps everything.
     */
    public static List<String> sample(Set<String> values, int cap, long seed) {
        List<String> sorted = new ArrayList<>(new TreeSet<>(values));
        if (cap <= 0 || sorted.size() <= cap) {
            return sorted;
        }
        Collections.shuffle(sorted, new Random(seed));
        return new ArrayList<>(sorted.subList(0, cap));
    }

    /** Parses a numeric cell; {@code null} for anything that is not a finite number. */
    public static Double parseNumber(String v) {
        String t = v.trim();
        if (t.isEmpty()) {
            return null;
        }
        char c = t.charAt(0);
        if (!(Character.isDigit(c) || c == '-' || c == '+' || c == '.')) {
            return null;
        }
        try {
            double d = Double.parseDouble(t);
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static double numericFraction(List<String> nonNull) {
        if (nonNull.isEmpty()) {
            return 0.0;
        }
        int n = 0;
        for (String v : nonNull) {
            if (parseNumber(v) != null) {
                n++;
            }
        }
        return (double) n / nonNull.size();
    }

    public static double[] numbers(List<String> nonNull) {
        List<Double> out = new ArrayList<>();
        for (String v : nonNull) {
            Double d = parseNumber(v);
            if (d != null) {
                out.add(d);
            }
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public static double[] lengths(List<String> nonNull) {
        double[] out = new double[nonNull.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = nonNull.get(i).length();
        }
        return out;
    }

    public static Map<String, Double> frequencies(List<String> values) {
        Map<String, Double> f = new HashMap<>();
        for (String v : values) {
            f.merge(v, 1.0, Double::sum);
        }
        double n = values.size();
        if (n > 0) {
            f.replaceAll((k, c) -> c / n);
        }
        return f;
    }

    /** Jensen-Shannon divergence with log base 2, in [0, 1]. */
    public static double jensenShannon(Map<String, Double> p, Map<String, Double> q) {
        Set<String> keys = new LinkedHashSet<>(p.keySet());
        keys.addAll(q.keySet());
        double js = 0;
        for (String k : keys) {
            double a = p.getOrDefault(k, 0.0);
            double b = q.getOrDefault(k, 0.0);
            double m = 0.5 * (a + b);
            if (a > 0) {
                js += 0.5 * a * Math.log(a / m);
            }
            if (b > 0) {
                js += 0.5 * b * Math.log(b / m);
            }
        }
        return Math.max(0.0, Math.min(1.0, js / Math.log(2)));
    }
}
