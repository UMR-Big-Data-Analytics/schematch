package de.uni_marburg.schematch.matching.similarity.label;

import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import de.uni_marburg.schematch.similarity.SimilarityMeasure;
import de.uni_marburg.schematch.similarity.string.Smoa;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** SMOA (Stoilos et al., ISWC 2005) on the whole label, mapped to [0, 1]; see {@link Smoa}. */
@Data
@EqualsAndHashCode(callSuper = true)
public class SmoaMatcher extends ParameterisedLabelMatcher {
    private int minSubstring = 3;

    @Override
    protected SimilarityMeasure<String> measure(TablePair tablePair) {
        return new Smoa(minSubstring);
    }
}
