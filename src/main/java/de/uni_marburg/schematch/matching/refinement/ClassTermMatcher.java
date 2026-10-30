package de.uni_marburg.schematch.matching.refinement;

import de.uni_marburg.schematch.data.Database;
import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.matching.similarityFlooding.ColumnClasses;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Locale;

/**
 * Class term q+ + beta D + weight * [same class] (rung (b)). With {@code classes=SHAPE} (default) a column's class
 * is its dominant value shape if at least half of its non-empty values have it, else MIXED; MIXED equals MIXED.
 * weight 0.2 is Amendment 5's frozen gamma for pattern classes (sf-matcher-study/reference/
 * AMENDMENT_ADDITIVE_DRAFT.md, HB1; A_p in layer3b/score_classfull.py). Classes are layer3b/f7.py column_classes.
 *
 * <p>{@code classes=SEMTYPE} uses the rule-based semantic types of layer3b/f13.py instead (mixed equals mixed);
 * the registered gamma for them is 0.1 (A_s = q+ + 5 D + 0.1 eq_s in score_classfull.py), so set
 * {@code weight: [0.1]} with it. Both class kinds come from {@link ColumnClasses}, the same function SF's native
 * class nodes use.
 */
@Getter
@Setter
@EqualsAndHashCode(callSuper = true)
public final class ClassTermMatcher extends AdditiveTermMatcher {
    static final String MIXED = ColumnClasses.MIXED;
    private double weight = 0.2;
    private String classes = "SHAPE";

    @Override
    double[][] term(float[][] scores, Scenario scenario) {
        requireFinite(weight);
        ColumnClasses.Kind kind = kind();
        String[] source = ColumnClasses.classes(scenario.getSourceDatabase(), kind);
        String[] target = ColumnClasses.classes(scenario.getTargetDatabase(), kind);
        double[][] out = new double[source.length][target.length];
        for (int i = 0; i < source.length; i++) for (int j = 0; j < target.length; j++)
            out[i][j] = source[i].equals(target[j]) ? weight : 0.0;
        return out;
    }

    private ColumnClasses.Kind kind() {
        try {
            return ColumnClasses.Kind.valueOf(classes.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("classes must be SHAPE or SEMTYPE, not " + classes);
        }
    }

    /** One value-shape class per column, in matrix order. */
    static String[] classes(Database database) {
        return ColumnClasses.classes(database, ColumnClasses.Kind.SHAPE);
    }

    static String columnClass(List<String> values) {
        return ColumnClasses.shapeClass(values);
    }

    static String shape(String v) {
        return ColumnClasses.shape(v);
    }
}
