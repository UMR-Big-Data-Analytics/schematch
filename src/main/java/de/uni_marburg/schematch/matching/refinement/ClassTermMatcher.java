package de.uni_marburg.schematch.matching.refinement;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Database;
import de.uni_marburg.schematch.data.Scenario;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Class term q+ + beta D + weight * [same class] (rung (b)) with value-shape classes: a column's class is
 * its dominant value shape if at least half of its non-empty values have it, else MIXED; MIXED equals
 * MIXED. weight 0.2 is Amendment 5's frozen gamma for pattern classes (sf-matcher-study/reference/
 * AMENDMENT_ADDITIVE_DRAFT.md, HB1; A_p in layer3b/score_classfull.py). Classes are layer3b/f7.py
 * column_classes. The rule-based semantic-type classes (f13.py, gamma 0.1) are not ported.
 */
@Getter
@Setter
@EqualsAndHashCode(callSuper = true)
public final class ClassTermMatcher extends AdditiveTermMatcher {
    static final String MIXED = "MIXED";
    private double weight = 0.2;

    @Override
    double[][] term(float[][] scores, Scenario scenario) {
        requireFinite(weight);
        String[] source = classes(scenario.getSourceDatabase()), target = classes(scenario.getTargetDatabase());
        double[][] out = new double[source.length][target.length];
        for (int i = 0; i < source.length; i++) for (int j = 0; j < target.length; j++)
            out[i][j] = source[i].equals(target[j]) ? weight : 0.0;
        return out;
    }

    /** One class per column, in matrix order. */
    static String[] classes(Database database) {
        List<Column> columns = database.getColumns();
        String[] out = new String[columns.size()];
        for (int c = 0; c < out.length; c++) out[c] = columnClass(columns.get(c).getValues());
        return out;
    }

    static String columnClass(List<String> values) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (String v : values) {
            if (v.isEmpty()) continue;
            counts.merge(shape(v), 1, Integer::sum);
            total++;
        }
        String top = "";
        int n = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) if (e.getValue() > n) { top = e.getKey(); n = e.getValue(); }
        // Ties go to the shape seen first (Counter.most_common); an all-whitespace shape is no class.
        return total > 0 && 2L * n >= total && !top.chars().allMatch(ch -> ch == ' ') ? top : MIXED;
    }

    /**
     * Upper letter A, lower letter a, digit 9, whitespace ' ', any other character kept; runs collapsed;
     * at most 32 characters. Per code point with Python's str predicates, unlike PatternProfileMatcher.shape,
     * which works on UTF-16 units and does not count no-break spaces as whitespace.
     */
    static String shape(String v) {
        StringBuilder out = new StringBuilder();
        int length = 0, last = -1;
        for (int k = 0; k < v.length(); ) {
            int c = v.codePointAt(k);
            k += Character.charCount(c);
            int m = Character.isUpperCase(c) ? 'A' : Character.isLowerCase(c) ? 'a' : isDigit(c) ? '9'
                    : isSpace(c) ? ' ' : c;
            if (length > 0 && m == last) continue;
            out.appendCodePoint(m);
            last = m;
            if (++length >= 32) break;
        }
        return out.toString();
    }

    /** str.isdigit: decimal digits plus digit-valued other numbers such as superscripts. */
    private static boolean isDigit(int c) {
        if (Character.isDigit(c)) return true;
        if (Character.getType(c) != Character.OTHER_NUMBER) return false;
        int value = Character.getNumericValue(c);
        return value >= 0 && value <= 9;
    }

    /** str.isspace: Java whitespace plus the no-break spaces (Zs) and NEL. */
    private static boolean isSpace(int c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85;
    }
}
