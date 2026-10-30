package de.uni_marburg.schematch.similarity.string;

import de.uni_marburg.schematch.similarity.SimilarityMeasure;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Character q-gram similarity (Ukkonen 1992; Kondrak, SPIRE 2005). Strings are lower-cased and
 * padded with q-1 boundary symbols on both sides so that prefixes and suffixes count, then compared
 * by Jaccard or Dice over the q-gram sets, or cosine over the q-gram counts.
 */
public class QGram implements SimilarityMeasure<String> {
    public static final String JACCARD = "JACCARD";
    public static final String DICE = "DICE";
    public static final String COSINE = "COSINE";

    private final int q;
    private final String coefficient;
    private final boolean padding;

    public QGram(int q, String coefficient, boolean padding) {
        if (q < 1) {
            throw new IllegalArgumentException("q must be >= 1, got " + q);
        }
        String c = coefficient.toUpperCase(Locale.ROOT);
        if (!c.equals(JACCARD) && !c.equals(DICE) && !c.equals(COSINE)) {
            throw new IllegalArgumentException("Unknown q-gram coefficient: " + coefficient);
        }
        this.q = q;
        this.coefficient = c;
        this.padding = padding;
    }

    public Map<String, Integer> grams(String s) {
        String t = s.toLowerCase(Locale.ROOT);
        if (padding && q > 1) {
            String pad = "\u0001".repeat(q - 1);
            t = pad + t + pad;
        }
        Map<String, Integer> counts = new HashMap<>();
        if (t.length() < q) {
            if (!t.isEmpty()) {
                counts.put(t, 1);
            }
            return counts;
        }
        for (int i = 0; i + q <= t.length(); i++) {
            counts.merge(t.substring(i, i + q), 1, Integer::sum);
        }
        return counts;
    }

    @Override
    public float compare(String s1, String s2) {
        Map<String, Integer> a = grams(s1);
        Map<String, Integer> b = grams(s2);
        if (a.isEmpty() || b.isEmpty()) {
            return a.isEmpty() && b.isEmpty() ? 1f : 0f;
        }
        if (coefficient.equals(COSINE)) {
            double dot = 0, na = 0, nb = 0;
            for (Map.Entry<String, Integer> e : a.entrySet()) {
                na += (double) e.getValue() * e.getValue();
                Integer other = b.get(e.getKey());
                if (other != null) {
                    dot += (double) e.getValue() * other;
                }
            }
            for (int v : b.values()) {
                nb += (double) v * v;
            }
            return (float) (dot / Math.sqrt(na * nb));
        }
        int intersection = 0;
        for (String g : a.keySet()) {
            if (b.containsKey(g)) {
                intersection++;
            }
        }
        if (coefficient.equals(DICE)) {
            return (float) (2.0 * intersection / (a.size() + b.size()));
        }
        return (float) intersection / (a.size() + b.size() - intersection);
    }
}
