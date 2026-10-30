package de.uni_marburg.schematch.matching.control;

import de.uni_marburg.schematch.matching.TablePairMatcher;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Arrays;

/**
 * The uninformative seed: every pair scores {@code value}. As an SF seed it measures what the
 * graph structure alone produces, the "no seed" arm of a seed-quality sweep.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ConstantMatcher extends TablePairMatcher {
    private float value = 1.0f;

    @Override
    public float[][] match(TablePair tablePair) {
        float[][] sim = tablePair.getEmptySimMatrix();
        for (float[] row : sim) {
            Arrays.fill(row, value);
        }
        return sim;
    }
}
