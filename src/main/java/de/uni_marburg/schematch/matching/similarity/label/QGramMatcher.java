package de.uni_marburg.schematch.matching.similarity.label;

import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import de.uni_marburg.schematch.similarity.SimilarityMeasure;
import de.uni_marburg.schematch.similarity.string.QGram;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Character q-gram similarity of the whole label. {@code coefficient}: JACCARD (default), DICE or
 * COSINE. q=3 with padding is the label trigram channel ("char3_labels") of the harness's
 * embedding baseline, available here as a first-line matcher and hence as an SF seed.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QGramMatcher extends ParameterisedLabelMatcher {
    private int q = 3;
    private String coefficient = QGram.JACCARD;
    private Boolean padding = true;

    @Override
    protected SimilarityMeasure<String> measure(TablePair tablePair) {
        return new QGram(q, coefficient, Boolean.TRUE.equals(padding));
    }
}
