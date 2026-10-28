package de.uni_marburg.schematch.matching.ensemble;

import de.uni_marburg.schematch.matching.Matcher;
import de.uni_marburg.schematch.matchtask.MatchTask;
import de.uni_marburg.schematch.matchtask.matchstep.MatchingStep;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Locale;

/**
 * Uncertain matching over the K best 1:1 mappings: top-K mappings (Gal, J. Data Semantics VI,
 * 2006) with probabilistic schema mappings (Dong, Halevy &amp; Yu, VLDB 2007).
 *
 * <p>The {@code base} matcher (a JSON component, as in {@link CompositeMatcher}) gives a score
 * matrix; Murty's algorithm ({@link Assignments#kBest}) enumerates its {@code k} best 1:1 mappings;
 * each mapping m gets a probability, and a pair's score is the probability that it is in the
 * mapping, {@code P(i,j) = sum_m p(m) [ (i,j) in m ]}.
 *
 * <p>{@code weighting}: EXP (default), {@code p(m) ∝ exp((S̄(m) - S̄(best)) / temperature)} with
 * S̄ the mean pair score of the mapping; or UNIFORM, the top-K frequency of Gal's
 * stability argument. The output is a different kind of seed: it is concentrated on pairs that
 * survive in many near-optimal mappings, and zero elsewhere.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProbabilisticMappingMatcher extends Matcher {
    private String base = "{\"name\": \"LevenshteinMatcher\", \"package\": \"similarity.label\"}";
    private int k = 20;
    private float temperature = 0.02f;
    private String weighting = "EXP";

    @Override
    public float[][] match(MatchTask matchTask, MatchingStep matchStep) {
        Matcher inner = CompositeMatcher.components("[" + base + "]").get(0).matcher();
        float[][] s = inner.match(matchTask, matchStep);
        return probabilities(s, k, temperature, weighting);
    }

    static float[][] probabilities(float[][] s, int k, double temperature, String weighting) {
        int n = s.length;
        int m = n == 0 ? 0 : s[0].length;
        double[][] score = new double[n][m];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                score[i][j] = s[i][j];
            }
        }
        List<Assignments.Solution> top = Assignments.kBest(score, Math.max(1, k));
        float[][] out = new float[n][m];
        if (top.isEmpty()) {
            return out;
        }
        int pairs = Math.max(1, Math.min(n, m));
        double bestMean = top.get(0).score() / pairs;
        boolean uniform = weighting.toUpperCase(Locale.ROOT).equals("UNIFORM");
        if (!uniform && !weighting.equalsIgnoreCase("EXP")) {
            throw new IllegalArgumentException("Unknown weighting: " + weighting);
        }
        double[] w = new double[top.size()];
        double z = 0;
        for (int t = 0; t < top.size(); t++) {
            w[t] = uniform ? 1.0 : Math.exp((top.get(t).score() / pairs - bestMean) / temperature);
            z += w[t];
        }
        for (int t = 0; t < top.size(); t++) {
            int[] a = top.get(t).assignment();
            for (int i = 0; i < n; i++) {
                if (a[i] >= 0) {
                    out[i][a[i]] += (float) (w[t] / z);
                }
            }
        }
        return out;
    }
}
