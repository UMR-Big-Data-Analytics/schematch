package de.uni_marburg.schematch.matching.similarity.instance;

import java.util.Locale;

/**
 * Sorted probability-mass-function similarity, ignoring value identities.
 * L2 (default): 1 - ||sort(p)-sort(q)||_2 / sqrt(2), padded with zeros.
 * HELLINGER applies square roots to the masses first, as the legacy PMF variant does.
 * Empty strings are categories, preserving DistributionMatcher's historical null policy.
 * Unlike the legacy class, this matcher is independent of the smx.pmf.distance JVM property.
 */
public final class PMFMatcher extends DistributionMatcher {
    private String distance = "L2";

    public String getDistance() { return distance; }

    public void setDistance(String distance) {
        String metric = distance.toUpperCase(Locale.ROOT);
        if (!metric.equals("L2") && !metric.equals("HELLINGER")) {
            throw new IllegalArgumentException("PMF distance must be L2 or HELLINGER: " + distance);
        }
        this.distance = metric;
    }

    @Override
    protected String distanceMetric() { return distance; }

    @Override
    public String toString() { return "PMFMatcher(distance=" + distance + ")"; }
}
