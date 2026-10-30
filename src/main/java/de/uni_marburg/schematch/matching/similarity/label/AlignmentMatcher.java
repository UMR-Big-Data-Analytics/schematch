package de.uni_marburg.schematch.matching.similarity.label;

import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import de.uni_marburg.schematch.similarity.SimilarityMeasure;
import de.uni_marburg.schematch.similarity.string.Alignment;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Sequence-alignment label similarity: SMITH_WATERMAN (default), SMITH_WATERMAN_GOTOH or NEEDLEMAN_WUNSCH
 * (SimMetrics costs). See {@link Alignment}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AlignmentMatcher extends ParameterisedLabelMatcher {
    private String algorithm = Alignment.SMITH_WATERMAN;

    @Override
    protected SimilarityMeasure<String> measure(TablePair tablePair) {
        return new Alignment(algorithm);
    }
}
