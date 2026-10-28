package de.uni_marburg.schematch.matching;

import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.matching.ensemble.CompositeMatcher;
import de.uni_marburg.schematch.matchtask.MatchTask;
import de.uni_marburg.schematch.matchtask.matchstep.MatchingStep;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.List;

/** A matrix transformation: second-line RAW_FIRST_LINE/PREVIOUS input, or an inline first-line base component. */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class MatrixRefinementMatcher extends Matcher {
    private String initialMatrix = "RAW_FIRST_LINE";
    private Integer baseMatcherIndex = 0;
    /** Optional JSON component, e.g. {"name":"PMFMatcher","package":"similarity.instance"}. */
    private String base = "";

    public abstract float[][] refine(float[][] scores, Scenario scenario);

    @Override
    public float[][] match(MatchTask task, MatchingStep step) {
        float[][] scores;
        if (base != null && !base.isBlank()) {
            scores = CompositeMatcher.components("[" + base + "]").get(0).matcher().match(task, step);
        } else {
            if (step == null || step.getLine() != 2) {
                throw new IllegalArgumentException("Set an inline base or use this refinement on the second line");
            }
            List<Matcher> first = task.getFirstLineMatchers();
            if (first == null || baseMatcherIndex == null || baseMatcherIndex < 0 || baseMatcherIndex >= first.size()) {
                throw new IllegalArgumentException("Invalid baseMatcherIndex: " + baseMatcherIndex);
            }
            Matcher matcher = first.get(baseMatcherIndex);
            if ("RAW_FIRST_LINE".equalsIgnoreCase(initialMatrix)) scores = task.getFirstLineSimMatrix(matcher);
            else if ("PREVIOUS".equalsIgnoreCase(initialMatrix)) scores = task.getSimMatrixFromPreviousMatchStep(step, matcher);
            else throw new IllegalArgumentException("initialMatrix must be RAW_FIRST_LINE or PREVIOUS");
        }
        return refine(scores, task.getScenario());
    }
}
