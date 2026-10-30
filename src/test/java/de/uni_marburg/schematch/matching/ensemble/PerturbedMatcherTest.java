package de.uni_marburg.schematch.matching.ensemble;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.uni_marburg.schematch.utils.NumpyRandom;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Reference values come from the original exploratory perturbation function and NumPy
 * (perturbation_reference.json records the source hash and NumPy version).
 */
class PerturbedMatcherTest {
    private static JsonNode reference() throws IOException {
        try (InputStream in = PerturbedMatcherTest.class.getResourceAsStream("perturbation_reference.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    void numpyGeneratorDrawsMatch() throws IOException {
        Iterator<Map.Entry<String, JsonNode>> draws = reference().get("draws").fields();
        while (draws.hasNext()) {
            Map.Entry<String, JsonNode> entry = draws.next();
            NumpyRandom rng = new NumpyRandom(Long.parseUnsignedLong(entry.getKey()));
            for (JsonNode expected : entry.getValue()) {
                assertEquals(expected.asDouble(), rng.random(), 0.0, "seed " + entry.getKey());
            }
        }
    }

    @Test
    void perturbationMatchesOriginal() throws IOException {
        JsonNode ref = reference();
        JsonNode qNode = ref.get("q");
        float[][] q = new float[qNode.size()][qNode.get(0).size()];
        for (int i = 0; i < q.length; i++) {
            for (int j = 0; j < q[0].length; j++) {
                q[i][j] = (float) qNode.get(i).get(j).asDouble();
            }
        }
        for (JsonNode c : ref.get("cases")) {
            String variant = c.get("variant").asText();
            String mode = variant.equals("flat") ? "FLATTEN" : "UNIFORM";
            double noise = variant.equals("flat") ? 0 : Integer.parseInt(variant.substring(1).split("_")[0]) / 100.0;
            int draw = variant.contains("_d") ? Integer.parseInt(variant.substring(variant.indexOf("_d") + 2)) : 0;
            String key = PerturbedMatcher.seedKey(new PerturbedMatcher().getKey(), "levenshtein", "Example", "case", noise, draw);
            if (!variant.equals("p0") && !variant.equals("flat")) {
                assertEquals(c.get("seed_key").asText(), key);
            }
            float[][] out = PerturbedMatcher.perturb(q, mode, noise, key);
            for (int i = 0; i < q.length; i++) {
                for (int j = 0; j < q[0].length; j++) {
                    assertEquals((float) c.get("expected").get(i).get(j).asDouble(), out[i][j], 0f, variant + " " + i + "," + j);
                }
            }
        }
    }

    @Test
    void rejectsUnknownModesAndNegativeNoise() {
        float[][] q = {{0.5f}};
        assertThrows(IllegalArgumentException.class, () -> PerturbedMatcher.perturb(q, "GAUSSIAN", 0.5, "k"));
        assertThrows(IllegalArgumentException.class, () -> PerturbedMatcher.perturb(q, "UNIFORM", -0.1, "k"));
    }
}
