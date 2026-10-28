package de.uni_marburg.schematch.matching.similarity.instance;

import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matching.TablePairMatcher;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Format/pattern profiles of the cell values (the "format" evidence of D3L, Bogatu et al.
 * ICDE 2020, and of Sherlock's character features, in their simplest form).
 *
 * <p>{@code granularity}:
 * <ul>
 *     <li>SHAPE (default): every cell becomes its shape (upper letter A, lower letter a, digit 9,
 *     whitespace ' ', every other character kept), runs collapsed when {@code collapse} is set,
 *     truncated to {@code maxLength}; columns are compared by {@code 1 - JSD} of their shape
 *     distributions. "2024-01-31" and "1999-12-01" share the shape "9-9-9".</li>
 *     <li>CHARCLASS: the fractions of upper, lower, digit, whitespace and other characters over
 *     all cells, compared by {@code 1 - L1 / 2}.</li>
 * </ul>
 * Both survive opaque labels; SHAPE does not survive value hashing (every MD5 has shape a9a9...).
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PatternProfileMatcher extends TablePairMatcher {
    private String granularity = "SHAPE";
    private Boolean collapse = true;
    private int maxLength = 32;

    static String shape(String v, boolean collapse, int maxLength) {
        StringBuilder sb = new StringBuilder();
        char last = 0;
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            char m;
            if (Character.isUpperCase(c)) {
                m = 'A';
            } else if (Character.isLowerCase(c)) {
                m = 'a';
            } else if (Character.isDigit(c)) {
                m = '9';
            } else if (Character.isWhitespace(c)) {
                m = ' ';
            } else {
                m = c;
            }
            if (collapse && sb.length() > 0 && m == last) {
                continue;
            }
            sb.append(m);
            last = m;
            if (maxLength > 0 && sb.length() >= maxLength) {
                break;
            }
        }
        return sb.toString();
    }

    static double[] charClasses(List<String> values) {
        double[] f = new double[5];
        double total = 0;
        for (String v : values) {
            for (int k = 0; k < v.length(); k++) {
                char c = v.charAt(k);
                int cls = Character.isUpperCase(c) ? 0 : Character.isLowerCase(c) ? 1
                        : Character.isDigit(c) ? 2 : Character.isWhitespace(c) ? 3 : 4;
                f[cls]++;
                total++;
            }
        }
        if (total > 0) {
            for (int k = 0; k < 5; k++) {
                f[k] /= total;
            }
        }
        return f;
    }

    @Override
    public float[][] match(TablePair tablePair) {
        Table s = tablePair.getSourceTable();
        Table t = tablePair.getTargetTable();
        String g = granularity.toUpperCase(Locale.ROOT);
        if (!g.equals("SHAPE") && !g.equals("CHARCLASS")) {
            throw new IllegalArgumentException("Unknown pattern granularity: " + granularity);
        }
        boolean col = Boolean.TRUE.equals(collapse);
        List<Object> left = new ArrayList<>();
        for (int i = 0; i < s.getNumColumns(); i++) {
            left.add(profile(ColumnValues.nonNull(s.getColumn(i)), g, col));
        }
        List<Object> right = new ArrayList<>();
        for (int j = 0; j < t.getNumColumns(); j++) {
            right.add(profile(ColumnValues.nonNull(t.getColumn(j)), g, col));
        }
        float[][] sim = tablePair.getEmptySimMatrix();
        for (int i = 0; i < left.size(); i++) {
            for (int j = 0; j < right.size(); j++) {
                sim[i][j] = (float) compare(left.get(i), right.get(j), g);
            }
        }
        return sim;
    }

    private Object profile(List<String> values, String g, boolean col) {
        if (values.isEmpty()) {
            return null;
        }
        if (g.equals("CHARCLASS")) {
            return charClasses(values);
        }
        List<String> shapes = new ArrayList<>(values.size());
        for (String v : values) {
            shapes.add(shape(v, col, maxLength));
        }
        return ColumnValues.frequencies(shapes);
    }

    @SuppressWarnings("unchecked")
    private static double compare(Object a, Object b, String g) {
        if (a == null || b == null) {
            return 0.0;
        }
        if (g.equals("CHARCLASS")) {
            double[] x = (double[]) a;
            double[] y = (double[]) b;
            double l1 = 0;
            for (int k = 0; k < x.length; k++) {
                l1 += Math.abs(x[k] - y[k]);
            }
            return 1.0 - l1 / 2.0;
        }
        return 1.0 - ColumnValues.jensenShannon((Map<String, Double>) a, (Map<String, Double>) b);
    }
}
