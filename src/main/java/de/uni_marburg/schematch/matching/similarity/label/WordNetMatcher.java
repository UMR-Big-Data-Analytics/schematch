package de.uni_marburg.schematch.matching.similarity.label;

import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import de.uni_marburg.schematch.preprocessing.LabelNormalizer;
import de.uni_marburg.schematch.similarity.SimilarityMeasure;
import de.uni_marburg.schematch.similarity.lexical.WordNet;
import de.uni_marburg.schematch.similarity.string.MongeElkan;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Locale;

/**
 * Lexical-resource label matcher over WordNet nouns (see {@link WordNet}).
 *
 * <p>Labels are tokenised ({@code normalization}, default EXPAND so that "dept" reaches
 * "department"), adjacent tokens are merged into WordNet compounds, and the token lists are compared
 * by symmetric Monge-Elkan with the WordNet word similarity as the inner measure. A token pair in
 * which either token is not a WordNet noun is scored by {@code fallback}: EQUAL (default, 1 for
 * identical tokens), JARO_WINKLER, or NONE (0).
 *
 * <p>{@code measure}: WUP (default), PATH, LCH, RES, LIN or JCN.
 * {@code wordnetPath}: NLTK's wordnet.zip or an unpacked dictionary directory; empty means
 * {@code ~/nltk_data/corpora/wordnet.zip}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WordNetMatcher extends ParameterisedLabelMatcher {
    private String measure = WordNet.WUP;
    private String wordnetPath = "";
    private String normalization = LabelNormalizer.EXPAND;
    private String fallback = "EQUAL";
    /** NLTK wordnet_ic file for corpus IC (RES/LIN/JCN); empty = intrinsic IC. */
    private String icFile = "";

    @Override
    protected SimilarityMeasure<String> measure(TablePair tablePair) {
        WordNet wordNet = WordNet.load(wordnetPath, icFile);
        String m = measure.toUpperCase(Locale.ROOT);
        SimilarityMeasure<String> back = fallback.equalsIgnoreCase("NONE")
                ? (a, b) -> 0f : innerMeasure(fallback);
        SimilarityMeasure<String> inner = (a, b) -> {
            double s = wordNet.wordSimilarity(a, b, m);
            return s < 0 ? back.compare(a, b) : (float) s;
        };
        return (a, b) -> {
            List<String> x = wordNet.compounds(LabelNormalizer.tokens(a, normalization), 3);
            List<String> y = wordNet.compounds(LabelNormalizer.tokens(b, normalization), 3);
            return (float) MongeElkan.symmetric(x, y, inner);
        };
    }
}
