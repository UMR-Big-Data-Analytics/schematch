package de.uni_marburg.schematch.matching.similarity.instance;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matching.TablePairMatcher;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares columns by their sorted probability-mass functions.
 *
 * Value identities are deliberately discarded: only the multiplicities of
 * distinct values remain.  This is the probability profile underlying
 * PRISMA's distribution attribute and is invariant when values are renamed
 * within each column, including the shipped per-side MD5 transformation.
 */
public class DistributionMatcher extends TablePairMatcher {
    private static final double MAX_PMF_DISTANCE = Math.sqrt(2.0);

    /**
     * Distance between two sorted probability profiles.
     *
     * The default, L2, is what every result before 2026-09-05 used. FINDINGS 55
     * measured four distances on identical support alignment, selecting on the
     * 44 exploration scenarios and scoring the 338 once: blended into the seed,
     * Hellinger is worth +0.031 F1 (p=2e-12), +0.023 Recall@GT and +0.037 MRR,
     * significant in every scenario type.
     *
     * The gain is a fusion effect, not a better channel. Standalone, Hellinger
     * is a wash on F1 and significantly *worse* on Recall@GT and MRR: taking
     * square roots compresses the large masses and expands the small ones, so
     * after the seed's affine range-alignment the channel disagrees with
     * Levenshtein in more useful places. Do not adopt it for standalone use --
     * notably not on the external v3 corpus, where PMF stands alone.
     */
    private static final String DISTANCE =
            System.getProperty("smx.pmf.distance", "l2").toLowerCase();

    @Override
    public float[][] match(TablePair tablePair) {
        Table source = tablePair.getSourceTable();
        Table target = tablePair.getTargetTable();
        float[][] result = tablePair.getEmptySimMatrix();
        for (int row = 0; row < source.getNumColumns(); row++) {
            for (int column = 0; column < target.getNumColumns(); column++) {
                result[row][column] = distributionSimilarity(
                        source.getColumn(row).getValues(),
                        target.getColumn(column).getValues(), distanceMetric());
            }
        }
        return result;
    }

    static float distributionSimilarity(List<String> source, List<String> target) {
        return distributionSimilarity(source, target, DISTANCE);
    }

    /** Legacy configurations keep the JVM property; PMFMatcher uses an explicit instance parameter. */
    protected String distanceMetric() {
        return DISTANCE;
    }

    static float distributionSimilarity(List<String> source, List<String> target, String distance) {
        if (source.isEmpty() || target.isEmpty()) {
            return source.isEmpty() && target.isEmpty() ? 1.0f : 0.0f;
        }
        double[] left = probabilityProfile(source);
        double[] right = probabilityProfile(target);
        int length = Math.max(left.length, right.length);
        boolean hellinger = "hellinger".equalsIgnoreCase(distance);
        double squaredDistance = 0.0;
        for (int i = 0; i < length; i++) {
            double leftValue = i < left.length ? left[i] : 0.0;
            double rightValue = i < right.length ? right[i] : 0.0;
            if (hellinger) {
                leftValue = Math.sqrt(leftValue);
                rightValue = Math.sqrt(rightValue);
            }
            double difference = leftValue - rightValue;
            squaredDistance += difference * difference;
        }
        double similarity = 1.0 - Math.sqrt(squaredDistance) / MAX_PMF_DISTANCE;
        return (float) Math.max(0.0, Math.min(1.0, similarity));
    }

    private static double[] probabilityProfile(List<String> values) {
        Map<String, Integer> counts = new HashMap<>();
        for (String value : values) {
            counts.merge(value, 1, Integer::sum);
        }
        List<Integer> sorted = new ArrayList<>(counts.values());
        sorted.sort(Comparator.reverseOrder());
        double[] profile = new double[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            profile[i] = (double) sorted.get(i) / values.size();
        }
        return profile;
    }
}
