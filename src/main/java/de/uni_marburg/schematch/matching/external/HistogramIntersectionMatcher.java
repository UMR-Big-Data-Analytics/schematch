package de.uni_marburg.schematch.matching.external;

/** Histogram intersection of numeric values on a shared 20-bin grid. Served by seed_server.py (default port 5101). */
public final class HistogramIntersectionMatcher extends NamedPythonMatcher {
    public HistogramIntersectionMatcher() { super("histogram_intersection"); }
}
