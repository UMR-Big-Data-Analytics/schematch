package de.uni_marburg.schematch.matching.similarity.instance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributionMatcherTest {
    @Test
    void ignoresValueIdentityButPreservesMultiplicity() {
        float plain = DistributionMatcher.distributionSimilarity(
                List.of("a", "a", "b", "c"),
                List.of("x", "x", "y", "z"));
        float renamed = DistributionMatcher.distributionSimilarity(
                List.of("md5-1", "md5-1", "md5-2", "md5-3"),
                List.of("salt-1", "salt-1", "salt-2", "salt-3"));

        assertEquals(1.0f, plain, 1e-7);
        assertEquals(plain, renamed, 1e-7);
    }

    @Test
    void separatesDifferentFrequencyProfiles() {
        float identical = DistributionMatcher.distributionSimilarity(
                List.of("a", "a", "b", "b"),
                List.of("x", "x", "y", "y"));
        float different = DistributionMatcher.distributionSimilarity(
                List.of("a", "a", "b", "b"),
                List.of("x", "x", "x", "x"));

        assertEquals(1.0f, identical, 1e-7);
        assertTrue(different < identical);
        assertTrue(different >= 0.0f);
    }
}
