package de.uni_marburg.schematch.matching.external;

/** IoU of numeric or date [min, max] ranges. Served by seed_server.py (default port 5101). */
public final class RangeOverlapMatcher extends NamedPythonMatcher {
    public RangeOverlapMatcher() { super("range_overlap"); }
}
