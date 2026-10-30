package de.uni_marburg.schematch.matching.refinement;

import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matching.MatrixRefinementMatcher;
import de.uni_marburg.schematch.matching.similarity.label.TableQualifiedNameMatcher;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** X105 base + weight * Q (default 0.5); no solver, Python dependency, or tie noise. */
@Data
@EqualsAndHashCode(callSuper = true)
public final class QualifiedNameRefinementMatcher extends MatrixRefinementMatcher {
    private double weight = 0.5;

    @Override
    public float[][] refine(float[][] scores, Scenario scenario) {
        if (!Double.isFinite(weight)) throw new IllegalArgumentException("weight must be finite");
        ScoreMatrices.validate(scores);
        if (scores.length != scenario.getSourceDatabase().getNumColumns()) throw new IllegalArgumentException("Source dimensions differ");
        for (float[] row : scores) if (row.length != scenario.getTargetDatabase().getNumColumns())
            throw new IllegalArgumentException("Target dimensions differ");
        float[][] out = new float[scores.length][];
        for (int i = 0; i < scores.length; i++) out[i] = scores[i].clone();
        for (Table source : scenario.getSourceDatabase().getTables()) {
            for (Table target : scenario.getTargetDatabase().getTables()) {
                for (int i = 0; i < source.getNumColumns(); i++) {
                    for (int j = 0; j < target.getNumColumns(); j++) {
                        out[source.getOffset() + i][target.getOffset() + j] += (float) (weight *
                                TableQualifiedNameMatcher.similarity(source.getName(), source.getColumn(i).getLabel(),
                                        target.getName(), target.getColumn(j).getLabel()));
                    }
                }
            }
        }
        return out;
    }
}
