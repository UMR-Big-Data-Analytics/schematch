package de.uni_marburg.schematch.matching.similarity.label;

import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import de.uni_marburg.schematch.preprocessing.LabelNormalizer;
import de.uni_marburg.schematch.similarity.SimilarityMeasure;
import de.uni_marburg.schematch.similarity.string.MongeElkan;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Symmetric Monge-Elkan over label tokens (Monge &amp; Elkan, KDD 1996; the hybrid compared by
 * Cohen, Ravikumar &amp; Fienberg, IIWeb 2003).
 *
 * <p>{@code inner}: JARO_WINKLER (default), LEVENSHTEIN, PREFIX_SUFFIX, QGRAM3 or EQUAL.
 * {@code normalization}: SPLIT (default), EXPAND or STEM, see {@link LabelNormalizer}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MongeElkanMatcher extends ParameterisedLabelMatcher {
    private String inner = "JARO_WINKLER";
    private String normalization = LabelNormalizer.SPLIT;

    @Override
    protected SimilarityMeasure<String> measure(TablePair tablePair) {
        SimilarityMeasure<String> innerMeasure = innerMeasure(inner);
        return (a, b) -> (float) MongeElkan.symmetric(
                LabelNormalizer.tokens(a, normalization), LabelNormalizer.tokens(b, normalization), innerMeasure);
    }
}
