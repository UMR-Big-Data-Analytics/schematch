package de.uni_marburg.schematch.matching.ensemble;

import de.uni_marburg.schematch.matching.Matcher;
import de.uni_marburg.schematch.matchtask.MatchTask;
import de.uni_marburg.schematch.matchtask.matchstep.MatchingStep;
import de.uni_marburg.schematch.utils.NumpyRandom;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Robustness control for any initial matcher: Melnik, Garcia-Molina &amp; Rahm (ICDE 2002, Sec. 6)
 * randomise every initial similarity by up to ±{@code noise} and clip negatives to zero,
 * {@code q' = max(0, q (1 + x))} with {@code x ~ U[-noise, noise]} per cell; FLATTEN instead sets
 * every positive cell to the mean of the positive cells.
 *
 * <p>The {@code base} matcher is a JSON component, as in {@link CompositeMatcher}. Draws are
 * NumPy-compatible ({@link NumpyRandom}) and seeded by the first 16 hex digits of SHA-256 of
 * {@code key}, whose placeholders are {@code {label}} (default: the base matcher's name),
 * {@code {dataset}}, {@code {scenario}}, {@code {percent}} and {@code {draw}}. The default key
 * reproduces the original exploratory perturbation (label = its initial-matrix name) cell for cell.
 * {@code noise: 0} returns the base matrix unchanged.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PerturbedMatcher extends Matcher {
    private String base = "{\"name\": \"LevenshteinMatcher\", \"package\": \"similarity.label\"}";
    private double noise = 0.5;
    private int draw = 0;
    private String mode = "UNIFORM";
    private String label = "";
    private String key = "R1/{label}/{dataset}/{scenario}/p{percent}_d{draw}";

    @Override
    public float[][] match(MatchTask matchTask, MatchingStep matchStep) {
        Matcher inner = CompositeMatcher.components("[" + base + "]").get(0).matcher();
        float[][] q = inner.match(matchTask, matchStep);
        String dataset = matchTask.getDataset() == null ? "" : matchTask.getDataset().getName();
        String name = label.isEmpty() ? inner.getClass().getSimpleName() : label;
        return perturb(q, mode, noise, seedKey(key, name, dataset, matchTask.getScenario().getName(), noise, draw));
    }

    static String seedKey(String template, String label, String dataset, String scenario, double noise, int draw) {
        return template.replace("{label}", label).replace("{dataset}", dataset).replace("{scenario}", scenario)
                .replace("{percent}", Long.toString(Math.round(noise * 100))).replace("{draw}", Integer.toString(draw));
    }

    static long seedFor(String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return Long.parseUnsignedLong(HexFormat.of().formatHex(hash).substring(0, 16), 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static float[][] perturb(float[][] q, String mode, double noise, String key) {
        int n = q.length;
        int m = n == 0 ? 0 : q[0].length;
        float[][] out = new float[n][m];
        switch (mode.toUpperCase(Locale.ROOT)) {
            case "FLATTEN" -> {
                double sum = 0;
                int positive = 0;
                for (float[] row : q) {
                    for (float v : row) {
                        if (v > 0) {
                            sum += v;
                            positive++;
                        }
                    }
                }
                float mean = positive == 0 ? 0f : (float) (sum / positive);
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < m; j++) {
                        out[i][j] = q[i][j] > 0 ? mean : 0f;
                    }
                }
            }
            case "UNIFORM" -> {
                if (noise < 0) {
                    throw new IllegalArgumentException("noise must be non-negative");
                }
                if (noise == 0) {
                    for (int i = 0; i < n; i++) {
                        out[i] = q[i].clone();
                    }
                    return out;
                }
                NumpyRandom rng = new NumpyRandom(seedFor(key));
                for (int i = 0; i < n; i++) {                 // C order, as numpy's uniform(size=q.shape)
                    for (int j = 0; j < m; j++) {
                        out[i][j] = (float) Math.max(0.0, q[i][j] * (1.0 + rng.uniform(-noise, noise)));
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unknown perturbation mode: " + mode);
        }
        return out;
    }
}
