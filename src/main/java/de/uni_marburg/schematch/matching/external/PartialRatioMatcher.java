package de.uni_marburg.schematch.matching.external;

/** rapidfuzz partial_ratio (best-aligned substring) of normalised labels. Served by seed_server.py (default port 5101). */
public final class PartialRatioMatcher extends NamedPythonMatcher {
    public PartialRatioMatcher() { super("partial_ratio"); }
}
