package de.uni_marburg.schematch.matching.similarity.label;

import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import de.uni_marburg.schematch.preprocessing.LabelNormalizer;
import de.uni_marburg.schematch.similarity.SimilarityMeasure;
import de.uni_marburg.schematch.similarity.lexical.WordNet;
import de.uni_marburg.schematch.similarity.string.MongeElkan;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.File;
import java.util.ArrayList;
import java.util.TreeSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Schema-aware label normalisation in the spirit of NORMS (Sorrentino, Bergamaschi, Gawinecki &amp;
 * Po, DKE 2010): abbreviations are expanded using the vocabulary of the <em>schemas being
 * matched</em>, not only a fixed dictionary, and the normalised labels are then compared.
 *
 * <p>A token is a candidate abbreviation when it is short ({@code <= maxAbbreviation} characters)
 * and, if WordNet is installed, not itself a WordNet noun. Its expansion is the longest full word
 * from the other schema's vocabulary (both tables' label tokens) that starts with the same letter
 * and contains the abbreviation's letters in order ("qty" -> "quantity", "cust" -> "customer"),
 * provided the word is at least 1.5 times as long. The static list of {@link LabelNormalizer} is
 * applied first. Expanded labels are compared by symmetric Monge-Elkan over Jaro-Winkler.
 *
 * <p>NORMS also interprets compound nouns with WordNet relations; that part is the
 * {@link WordNetMatcher}'s compound grouping and is not repeated here.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NormsMatcher extends ParameterisedLabelMatcher {
    private int maxAbbreviation = 4;
    private String inner = "JARO_WINKLER";

    static boolean subsequence(String abbr, String word) {
        if (abbr.isEmpty() || word.isEmpty() || abbr.charAt(0) != word.charAt(0)) {
            return false;
        }
        int i = 0;
        for (int k = 0; k < word.length() && i < abbr.length(); k++) {
            if (word.charAt(k) == abbr.charAt(i)) {
                i++;
            }
        }
        return i == abbr.length();
    }

    List<String> expand(String label, Set<String> vocabulary, WordNet wordNet) {
        List<String> out = new ArrayList<>();
        for (String t : LabelNormalizer.tokens(label, LabelNormalizer.EXPAND)) {
            String best = t;
            boolean candidate = t.length() <= maxAbbreviation && t.chars().allMatch(Character::isLetter)
                    && (wordNet == null || !wordNet.contains(t));
            if (candidate) {
                for (String w : vocabulary) {
                    if (w.length() >= 1.5 * t.length() && subsequence(t, w)
                            && (best.equals(t) || w.length() > best.length())) {
                        best = w;
                    }
                }
            }
            out.add(best);
        }
        return out;
    }

    @Override
    protected SimilarityMeasure<String> measure(TablePair tablePair) {
        Set<String> vocabulary = new LinkedHashSet<>();
        for (List<String> labels : List.of(tablePair.getSourceTable().getLabels(),
                tablePair.getTargetTable().getLabels())) {
            for (String l : labels) {
                for (String tok : LabelNormalizer.tokens(l, LabelNormalizer.EXPAND)) {
                    if (tok.length() > maxAbbreviation && tok.chars().allMatch(Character::isLetter)) {
                        vocabulary.add(tok);
                    }
                }
            }
        }
        WordNet wordNet = new File(WordNet.defaultPath()).isFile() ? WordNet.load("") : null;
        Set<String> vocab = new TreeSet<>(vocabulary); // sorted: ties break the same way every run
        SimilarityMeasure<String> innerMeasure = innerMeasure(inner);
        return (a, b) -> (float) MongeElkan.symmetric(expand(a, vocab, wordNet), expand(b, vocab, wordNet), innerMeasure);
    }
}
