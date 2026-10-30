package de.uni_marburg.schematch.matching.ensemble;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.uni_marburg.schematch.matching.Matcher;
import de.uni_marburg.schematch.matching.MatcherFactory;
import de.uni_marburg.schematch.matchtask.MatchTask;
import de.uni_marburg.schematch.matchtask.matchstep.MatchingStep;
import de.uni_marburg.schematch.utils.Configuration;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A composite (hybrid) matcher: runs several matchers and aggregates their matrices into one, so
 * that a combination is itself a single first-line matcher and therefore a single SF seed.
 *
 * <p>{@code components}: a JSON list,
 * {@code [{"name": "LevenshteinMatcher", "package": "similarity.label", "params": {...}, "weight": 1.0}, ...]}.
 * Each component is instantiated through {@link MatcherFactory}, exactly as a YAML entry would be.
 *
 * <p>{@code aggregation}:
 * <ul>
 *     <li>AVERAGE, MAX, MIN, WEIGHTED: COMA's aggregation strategies (Do &amp; Rahm, VLDB 2002;
 *     COMA++ / COMA 3.0). WEIGHTED uses the components' {@code weight}s.</li>
 *     <li>CONFIDENCE: a Harmony-style vote merger (Mork et al., J. Data Semantics XI, 2008): each
 *     component's vote on a pair is weighted by its confidence {@code |2 s - 1|}, so a matcher that
 *     is undecided (s near 0.5) contributes little. Harmony's exact confidence model is not
 *     published in enough detail to reproduce; this is the documented approximation.</li>
 *     <li>PREDICTOR: components weighted by a ground-truth-free matrix-quality predictor
 *     (Sagi &amp; Gal, VLDB J. 2013), chosen by {@code predictor}: DOMINANTS (the fraction of
 *     possible 1:1 pairs that are the maximum of both their row and column), STDEV (the standard
 *     deviation of the matrix) or MAXMEAN (the mean row maximum).</li>
 * </ul>
 * {@code SUM} adds weighted components without dividing by their weight sum (unlike WEIGHTED).
 * {@code normalise}: NONE (default) or MINMAX per component before aggregating. COMA's selection
 * step (thresholds, MaxN, Delta) is a matching decision, not a seed, and is not applied.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CompositeMatcher extends Matcher {
    private static final ObjectMapper JSON = new ObjectMapper();

    private String components = "[]";
    private String aggregation = "AVERAGE";
    private String predictor = "DOMINANTS";
    private String normalise = "NONE";

    public record Component(Matcher matcher, double weight) {
    }

    public static List<Component> components(String json) {
        try {
            List<Map<String, Object>> specs = JSON.readValue(json, new TypeReference<>() {
            });
            MatcherFactory factory = new MatcherFactory();
            List<Component> out = new ArrayList<>();
            for (Map<String, Object> spec : specs) {
                @SuppressWarnings("unchecked")
                Map<String, Object> params = spec.get("params") == null ? new HashMap<>()
                        : new HashMap<>((Map<String, Object>) spec.get("params"));
                Matcher m = factory.createMatcherInstance(new Configuration.MatcherConfiguration(
                        (String) spec.get("name"), (String) spec.get("package"), params));
                double w = spec.get("weight") == null ? 1.0 : ((Number) spec.get("weight")).doubleValue();
                out.add(new Component(m, w));
            }
            if (out.isEmpty()) {
                throw new IllegalArgumentException("CompositeMatcher needs at least one component");
            }
            return out;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Bad components JSON: " + json, e);
        }
    }

    @Override
    public float[][] match(MatchTask matchTask, MatchingStep matchStep) {
        List<Component> parts = components(components);
        List<float[][]> matrices = new ArrayList<>();
        for (Component c : parts) {
            float[][] s = c.matcher().match(matchTask, matchStep);
            matrices.add(normalise.equalsIgnoreCase("MINMAX") ? minMax(s) : s);
        }
        double[] weights = new double[parts.size()];
        String agg = aggregation.toUpperCase(Locale.ROOT);
        for (int k = 0; k < parts.size(); k++) {
            weights[k] = switch (agg) {
                case "WEIGHTED", "SUM" -> parts.get(k).weight();
                case "PREDICTOR" -> predict(matrices.get(k), predictor);
                default -> 1.0;
            };
        }
        return aggregate(matrices, weights, agg);
    }

    static float[][] aggregate(List<float[][]> matrices, double[] weights, String agg) {
        float[][] first = matrices.get(0);
        int n = first.length;
        int m = n == 0 ? 0 : first[0].length;
        float[][] out = new float[n][m];
        double wsum = 0;
        for (double w : weights) {
            wsum += w;
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                double v;
                switch (agg) {
                    case "MAX" -> {
                        v = Double.NEGATIVE_INFINITY;
                        for (float[][] s : matrices) {
                            v = Math.max(v, s[i][j]);
                        }
                    }
                    case "MIN" -> {
                        v = Double.POSITIVE_INFINITY;
                        for (float[][] s : matrices) {
                            v = Math.min(v, s[i][j]);
                        }
                    }
                    case "AVERAGE", "WEIGHTED", "PREDICTOR", "SUM" -> {
                        v = 0;
                        for (int k = 0; k < matrices.size(); k++) {
                            v += weights[k] * matrices.get(k)[i][j];
                        }
                        if (!agg.equals("SUM")) v = wsum > 0 ? v / wsum : 0;
                    }
                    case "CONFIDENCE" -> {
                        double num = 0, den = 0;
                        for (float[][] s : matrices) {
                            double c = Math.abs(2.0 * s[i][j] - 1.0);
                            num += c * s[i][j];
                            den += c;
                        }
                        double mean = 0;
                        for (float[][] s : matrices) {
                            mean += s[i][j];
                        }
                        v = den > 0 ? num / den : mean / matrices.size();
                    }
                    default -> throw new IllegalArgumentException("Unknown aggregation: " + agg);
                }
                out[i][j] = (float) v;
            }
        }
        return out;
    }

    public static float[][] minMax(float[][] s) {
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (float[] row : s) {
            for (float v : row) {
                if (!Float.isFinite(v)) throw new IllegalArgumentException("Non-finite composite score");
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
        }
        float[][] out = new float[s.length][];
        for (int i = 0; i < s.length; i++) {
            out[i] = new float[s[i].length];
            for (int j = 0; j < s[i].length; j++) {
                out[i][j] = hi > lo ? (float) ((s[i][j] - lo) / (hi - lo)) : 0f;
            }
        }
        return out;
    }

    /** Sagi &amp; Gal-style ground-truth-free quality predictors of a similarity matrix. */
    static double predict(float[][] s, String predictor) {
        int n = s.length;
        int m = n == 0 ? 0 : s[0].length;
        if (n == 0 || m == 0) {
            return 0.0;
        }
        switch (predictor.toUpperCase(Locale.ROOT)) {
            case "DOMINANTS": {
                double[] colMax = new double[m];
                java.util.Arrays.fill(colMax, Double.NEGATIVE_INFINITY);
                for (float[] row : s) {
                    for (int j = 0; j < m; j++) {
                        colMax[j] = Math.max(colMax[j], row[j]);
                    }
                }
                int dominants = 0;
                for (float[] row : s) {
                    double rowMax = Double.NEGATIVE_INFINITY;
                    for (float v : row) {
                        rowMax = Math.max(rowMax, v);
                    }
                    for (int j = 0; j < m; j++) {
                        if (row[j] > 0 && row[j] == rowMax && row[j] == colMax[j]) {
                            dominants++;
                            break;
                        }
                    }
                }
                return (double) dominants / Math.min(n, m);
            }
            case "STDEV": {
                double mean = 0;
                for (float[] row : s) {
                    for (float v : row) {
                        mean += v;
                    }
                }
                mean /= (double) n * m;
                double var = 0;
                for (float[] row : s) {
                    for (float v : row) {
                        var += (v - mean) * (v - mean);
                    }
                }
                return Math.sqrt(var / ((double) n * m));
            }
            case "MAXMEAN": {
                double sum = 0;
                for (float[] row : s) {
                    double rowMax = Double.NEGATIVE_INFINITY;
                    for (float v : row) {
                        rowMax = Math.max(rowMax, v);
                    }
                    sum += rowMax;
                }
                return sum / n;
            }
            default:
                throw new IllegalArgumentException("Unknown predictor: " + predictor);
        }
    }
}
