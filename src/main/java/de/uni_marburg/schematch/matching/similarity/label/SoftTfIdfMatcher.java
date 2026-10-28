package de.uni_marburg.schematch.matching.similarity.label;

import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import de.uni_marburg.schematch.preprocessing.LabelNormalizer;
import de.uni_marburg.schematch.similarity.SimilarityMeasure;
import de.uni_marburg.schematch.similarity.string.JaroWinkler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * SoftTF-IDF (Cohen, Ravikumar &amp; Fienberg, IIWeb 2003).
 *
 * <p>{@code SoftTFIDF(S,T) = sum over w in CLOSE(theta,S,T) of V(w,S) * V(w*,T) * D(w,T)}, where
 * {@code V'(w,S) = log(tf + 1) * log(N / df)}, V is V' L2-normalised, D(w,T) is the best Jaro-Winkler
 * score of w against the tokens of T, w* is that best token, and CLOSE keeps only the tokens with
 * {@code D > theta}. The corpus is the column labels of both tables of the pair. The original is
 * asymmetric; the two directions are averaged.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SoftTfIdfMatcher extends ParameterisedLabelMatcher {
    private float theta = 0.9f;
    private String normalization = LabelNormalizer.SPLIT;

    @Override
    protected SimilarityMeasure<String> measure(TablePair tablePair) {
        List<String> documents = new ArrayList<>(tablePair.getSourceTable().getLabels());
        documents.addAll(tablePair.getTargetTable().getLabels());
        Map<String, Integer> df = new HashMap<>();
        for (String d : documents) {
            for (String t : new HashSet<>(LabelNormalizer.tokens(d, normalization))) {
                df.merge(t, 1, Integer::sum);
            }
        }
        int n = documents.size();
        JaroWinkler jw = new JaroWinkler();
        return (a, b) -> {
            Map<String, Double> va = vector(LabelNormalizer.tokens(a, normalization), df, n);
            Map<String, Double> vb = vector(LabelNormalizer.tokens(b, normalization), df, n);
            return (float) (0.5 * (directed(va, vb, jw) + directed(vb, va, jw)));
        };
    }

    static Map<String, Double> vector(List<String> tokens, Map<String, Integer> df, int n) {
        Map<String, Integer> tf = new HashMap<>();
        for (String t : tokens) {
            tf.merge(t, 1, Integer::sum);
        }
        Map<String, Double> v = new HashMap<>();
        double norm = 0;
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            int d = Math.max(1, df.getOrDefault(e.getKey(), 1));
            double w = Math.log(e.getValue() + 1.0) * Math.log((double) n / d);
            v.put(e.getKey(), w);
            norm += w * w;
        }
        if (norm > 0) {
            double s = Math.sqrt(norm);
            v.replaceAll((k, w) -> w / s);
        }
        return v;
    }

    private double directed(Map<String, Double> s, Map<String, Double> t, JaroWinkler jw) {
        double sum = 0;
        for (Map.Entry<String, Double> e : s.entrySet()) {
            double best = 0;
            String bestToken = null;
            for (String u : t.keySet()) {
                double sim = jw.compare(e.getKey(), u);
                if (sim > best) {
                    best = sim;
                    bestToken = u;
                }
            }
            if (bestToken != null && best > theta) {
                sum += e.getValue() * t.get(bestToken) * best;
            }
        }
        return Math.min(1.0, sum);
    }
}
