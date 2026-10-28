package de.uni_marburg.schematch.matching.similarity.instance;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matching.TablePairMatcher;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * MinHash sketches of the distinct value sets (Broder 1997). {@code estimate}: JACCARD (default),
 * the fraction of agreeing minima, or CONTAINMENT, the larger of the two directional containments
 * recovered from the Jaccard estimate and the exact set sizes,
 * {@code C(A in B) = J (|A| + |B|) / ((1 + J) |A|)} (the conversion LSH Ensemble uses).
 *
 * <p>Values are hashed with 64-bit FNV-1a and permuted by a SplitMix64 finaliser per hash
 * function, seeded by {@code seed}. The sketch is the scalable stand-in for exact overlap; on the
 * benchmark sizes it is a check of how much a k = {@code numHashes} sketch loses.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MinHashMatcher extends TablePairMatcher {
    private int numHashes = 128;
    private int seed = 42;
    private String estimate = "JACCARD";
    private Boolean normalise = false;

    static long fnv1a(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        return h;
    }

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    long[] sketch(Set<String> values) {
        long[] mins = new long[numHashes];
        Arrays.fill(mins, Long.MAX_VALUE);
        long[] salts = new long[numHashes];
        for (int k = 0; k < numHashes; k++) {
            salts[k] = mix(seed * 0x9e3779b97f4a7c15L + k);
        }
        for (String v : values) {
            long h = fnv1a(v);
            for (int k = 0; k < numHashes; k++) {
                long p = mix(h ^ salts[k]);
                if (Long.compareUnsigned(p, mins[k]) < 0) {
                    mins[k] = p;
                }
            }
        }
        return mins;
    }

    @Override
    public float[][] match(TablePair tablePair) {
        Table s = tablePair.getSourceTable();
        Table t = tablePair.getTargetTable();
        boolean norm = Boolean.TRUE.equals(normalise);
        boolean containment = estimate.toUpperCase(Locale.ROOT).equals("CONTAINMENT");
        if (!containment && !estimate.equalsIgnoreCase("JACCARD")) {
            throw new IllegalArgumentException("Unknown MinHash estimate: " + estimate);
        }
        int n = s.getNumColumns();
        int m = t.getNumColumns();
        long[][] left = new long[n][];
        int[] leftSize = new int[n];
        for (int i = 0; i < n; i++) {
            Set<String> v = ColumnValues.distinct(s.getColumn(i), norm);
            left[i] = sketch(v);
            leftSize[i] = v.size();
        }
        long[][] right = new long[m][];
        int[] rightSize = new int[m];
        for (int j = 0; j < m; j++) {
            Set<String> v = ColumnValues.distinct(t.getColumn(j), norm);
            right[j] = sketch(v);
            rightSize[j] = v.size();
        }
        float[][] sim = tablePair.getEmptySimMatrix();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                if (leftSize[i] == 0 || rightSize[j] == 0) {
                    continue;
                }
                int agree = 0;
                for (int k = 0; k < numHashes; k++) {
                    if (left[i][k] == right[j][k]) {
                        agree++;
                    }
                }
                double jac = (double) agree / numHashes;
                if (!containment) {
                    sim[i][j] = (float) jac;
                } else {
                    double total = leftSize[i] + rightSize[j];
                    double inter = jac * total / (1.0 + jac);
                    double c = Math.max(inter / leftSize[i], inter / rightSize[j]);
                    sim[i][j] = (float) Math.min(1.0, c);
                }
            }
        }
        return sim;
    }
}
