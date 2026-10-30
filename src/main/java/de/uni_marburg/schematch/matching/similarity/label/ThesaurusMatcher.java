package de.uni_marburg.schematch.matching.similarity.label;

import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import de.uni_marburg.schematch.preprocessing.LabelNormalizer;
import de.uni_marburg.schematch.similarity.SimilarityMeasure;
import de.uni_marburg.schematch.similarity.lexical.Thesaurus;
import de.uni_marburg.schematch.similarity.string.MongeElkan;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * Thesaurus / controlled-vocabulary label matcher (Cupid's and COMA's synonym tables; UMLS or OMOP
 * vocabularies for biomedical schemas). See {@link Thesaurus} for the file formats.
 *
 * <p>Whole labels that are thesaurus terms ("zip code") are matched as one unit; otherwise label
 * tokens are grouped greedily into the longest thesaurus terms. Units are compared by symmetric
 * Monge-Elkan: 1 for identical units or synonyms, otherwise the {@code fallback} measure
 * (JARO_WINKLER default, EQUAL, or NONE).
 *
 * <p>{@code thesaurusFile} is required. sf-matcher-study/resources/thesaurus_generic.csv is a small
 * generic English example; it is an example, not a tuned resource.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ThesaurusMatcher extends ParameterisedLabelMatcher {
    private String thesaurusFile = "";
    private String format = "SYNSETS";
    private String normalization = LabelNormalizer.SPLIT;
    private String fallback = "JARO_WINKLER";

    List<String> units(String label, Thesaurus t) {
        List<String> tokens = LabelNormalizer.tokens(label, normalization);
        String whole = String.join(" ", tokens);
        if (t.contains(whole)) {
            return List.of(whole);
        }
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            int taken = 1;
            for (int len = Math.min(4, tokens.size() - i); len >= 2; len--) {
                String joined = String.join(" ", tokens.subList(i, i + len));
                if (t.contains(joined)) {
                    out.add(joined);
                    taken = len;
                    break;
                }
            }
            if (taken == 1) {
                out.add(tokens.get(i));
            }
            i += taken;
        }
        return out;
    }

    @Override
    protected SimilarityMeasure<String> measure(TablePair tablePair) {
        if (thesaurusFile == null || thesaurusFile.isBlank()) {
            throw new IllegalArgumentException("ThesaurusMatcher needs thesaurusFile");
        }
        Thesaurus t = Thesaurus.load(thesaurusFile, format);
        SimilarityMeasure<String> back = fallback.equalsIgnoreCase("NONE") ? (a, b) -> 0f : innerMeasure(fallback);
        SimilarityMeasure<String> inner = (a, b) -> a.equals(b) || t.synonyms(a, b) ? 1f : back.compare(a, b);
        return (a, b) -> (float) MongeElkan.symmetric(units(a, t), units(b, t), inner);
    }
}
