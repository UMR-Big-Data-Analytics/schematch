package de.uni_marburg.schematch.matching.similarity.label;

import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import de.uni_marburg.schematch.preprocessing.LabelNormalizer;
import de.uni_marburg.schematch.similarity.SimilarityMeasure;
import de.uni_marburg.schematch.similarity.string.PrefixSuffix;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Similarity Flooding's own seed: Melnik's prefix/suffix StringMatch, Valentine's default.
 *
 * <p>{@code useIdf=true} is Valentine's {@code PREFIX_SUFFIX_TFIDF}. Valentine computes IDF over the
 * names of every non-structural graph node, i.e. the table name and the column labels of both
 * sides (plus type names); here the "documents" are the two table names and all column labels of
 * the pair, {@code idf = log(N / df)}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PrefixSuffixMatcher extends ParameterisedLabelMatcher {
    private Boolean useIdf = false;

    @Override
    protected SimilarityMeasure<String> measure(TablePair tablePair) {
        if (!Boolean.TRUE.equals(useIdf)) {
            return new PrefixSuffix();
        }
        List<String> documents = new ArrayList<>();
        documents.add(tablePair.getSourceTable().getName());
        documents.add(tablePair.getTargetTable().getName());
        documents.addAll(tablePair.getSourceTable().getLabels());
        documents.addAll(tablePair.getTargetTable().getLabels());
        return new PrefixSuffix(idf(documents));
    }

    static Map<String, Double> idf(List<String> documents) {
        Map<String, Integer> df = new HashMap<>();
        for (String d : documents) {
            Set<String> seen = new HashSet<>();
            for (String t : LabelNormalizer.split(d)) {
                seen.add(t.toLowerCase(Locale.ROOT));
            }
            for (String t : seen) {
                df.merge(t, 1, Integer::sum);
            }
        }
        Map<String, Double> idf = new HashMap<>();
        int n = documents.size();
        for (Map.Entry<String, Integer> e : df.entrySet()) {
            idf.put(e.getKey(), Math.log((double) n / e.getValue()));
        }
        return idf;
    }
}
