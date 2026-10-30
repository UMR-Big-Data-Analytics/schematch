package de.uni_marburg.schematch.matching.refinement;

import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matching.MatrixRefinementMatcher;
import de.uni_marburg.schematch.matching.metadata.DatatypeMatcher;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * One-step additive terms (the information-matched baselines of the SF study): q+ = max(q, 0), plus
 * datatypeWeight * D, plus the subclass's weighted term. D is the datatype matcher with NaN cells set to 0
 * (columns without a non-null value; the study's datatype_nanfix). No iteration, solver, or tie noise.
 *
 * <p>datatypeWeight is beta = 5 (\dtUniBeta, chosen on Valentine's screen scenarios; reference
 * sf-matcher-study/reference/score_e2.py additive_rows). Computed in double, returned as float.
 */
@Getter
@Setter
@EqualsAndHashCode(callSuper = true)
public abstract class AdditiveTermMatcher extends MatrixRefinementMatcher {
    private double datatypeWeight = 5.0;

    /** The weighted term in scenario coordinates, or null for none. */
    abstract double[][] term(float[][] scores, Scenario scenario);

    @Override
    public float[][] refine(float[][] scores, Scenario scenario) {
        double[][] combined = combine(scores, scenario);
        float[][] out = new float[combined.length][];
        for (int i = 0; i < combined.length; i++) {
            out[i] = new float[combined[i].length];
            for (int j = 0; j < combined[i].length; j++) out[i][j] = (float) combined[i][j];
        }
        return out;
    }

    /** The term matrix before the float conversion. */
    double[][] combine(float[][] scores, Scenario scenario) {
        if (!Double.isFinite(datatypeWeight)) throw new IllegalArgumentException("datatypeWeight must be finite");
        ScoreMatrices.validate(scores, scenario);
        double[][] out = new double[scores.length][];
        for (int i = 0; i < scores.length; i++) {
            out[i] = new double[scores[i].length];
            for (int j = 0; j < scores[i].length; j++) out[i][j] = Math.max(scores[i][j], 0.0);
        }
        if (datatypeWeight != 0) add(out, datatypes(scenario), datatypeWeight);
        double[][] term = term(scores, scenario);
        if (term != null) add(out, term, 1.0);
        return out;
    }

    /** Result identity: the concrete class's parameters, beta, and the base choice. */
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(getClass().getSimpleName()).append("(datatypeWeight=").append(datatypeWeight);
        for (Field field : getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                out.append(", ").append(field.getName()).append('=').append(field.get(this));
            } catch (IllegalAccessException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        return out.append(", initialMatrix=").append(getInitialMatrix()).append(", baseMatcherIndex=").append(getBaseMatcherIndex())
                .append(", base=").append(getBase()).append(')').toString();
    }

    static void add(double[][] target, double[][] term, double weight) {
        for (int i = 0; i < target.length; i++) for (int j = 0; j < target[i].length; j++) target[i][j] += weight * term[i][j];
    }

    static void requireFinite(double weight) {
        if (!Double.isFinite(weight)) throw new IllegalArgumentException("weight must be finite");
    }

    /** D over all table pairs; NaN (no non-null value) counts as 0, like -Dsmx.datatype.nanAsZero=true. */
    static double[][] datatypes(Scenario scenario) {
        double[][] out = new double[scenario.getSourceDatabase().getNumColumns()][scenario.getTargetDatabase().getNumColumns()];
        DatatypeMatcher matcher = new DatatypeMatcher();
        for (Table source : scenario.getSourceDatabase().getTables()) {
            for (Table target : scenario.getTargetDatabase().getTables()) {
                float[][] block = matcher.match(new TablePair(source, target));
                for (int i = 0; i < block.length; i++) for (int j = 0; j < block[i].length; j++) {
                    float d = block[i][j];
                    out[source.getOffset() + i][target.getOffset() + j] = Float.isNaN(d) ? 0.0 : d;
                }
            }
        }
        return out;
    }
}
