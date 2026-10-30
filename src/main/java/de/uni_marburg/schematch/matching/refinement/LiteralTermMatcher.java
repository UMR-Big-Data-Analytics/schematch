package de.uni_marburg.schematch.matching.refinement;

import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.matching.similarityFlooding.LiteralEvidence;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * Literal term (rung (e)): q+ + beta D + weight * L, with L the literal evidence SF's second update adds
 * (LiteralEvidence: P^2 times the prefix/suffix literal seeds of SF as published, on the column pairs).
 * {@code form}:
 * <ul>
 *     <li>RESCALED (default): L / max L per scenario; weight 0.05 with beta 5 (\ldLamDL, X93's A_dL),
 *     or weight 0.1 with datatypeWeight 0 (\ldLamL, A_L); both frozen on Valentine's readable screen scenarios
 *     (sf-matcher-study/tuning/results/x93_scores/lambda.json). Reference: layer3b/score_x93.py l_matrix.</li>
 *     <li>RAW: unrescaled L; weight 0.3 with datatypeWeight 0 is X95's fixed weight (the median of SF's own
 *     two-update weight, \fwCaMed).</li>
 *     <li>TWO_UPDATE: weight ignored; c L with c = 2 / (M1 + 4), M1 the divisor of SF's first update on the
 *     input matrix. With datatypeWeight 0, q+ + c L ranks the pairs of a single table pair like SF stopped
 *     after two updates (layer3b/x94_stage1.py rule (a); review_checks/two_update_offset_check.py).</li>
 * </ul>
 */
@Getter
@Setter
@EqualsAndHashCode(callSuper = true)
public final class LiteralTermMatcher extends AdditiveTermMatcher {
    private double weight = 0.05;
    private String form = "RESCALED";

    @Override
    double[][] term(float[][] scores, Scenario scenario) {
        String f = form == null ? "" : form.toUpperCase();
        if (!f.equals("RESCALED") && !f.equals("RAW") && !f.equals("TWO_UPDATE"))
            throw new IllegalArgumentException("form must be RESCALED, RAW or TWO_UPDATE");
        if (!f.equals("TWO_UPDATE")) requireFinite(weight);
        LiteralEvidence.Result evidence = LiteralEvidence.compute(scenario.getSourceDatabase(), scenario.getTargetDatabase(), scores);
        double[][] literal = evidence.literal();
        double max = 0;
        for (double[] row : literal) for (double v : row) max = Math.max(max, v);
        double c = f.equals("TWO_UPDATE") ? 2.0 / (evidence.firstUpdateMaximum() + 4.0) : weight;
        for (double[] row : literal) for (int j = 0; j < row.length; j++)
            row[j] = f.equals("RESCALED") ? (max > 0 ? c * (row[j] / max) : c * row[j]) : c * row[j];
        return literal;
    }
}
