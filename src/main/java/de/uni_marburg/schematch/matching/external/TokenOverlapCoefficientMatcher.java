package de.uni_marburg.schematch.matching.external;

/** Overlap coefficient of label token sets; distinct from the q-gram OverlapLabelMatcher. Served by seed_server.py (default port 5101). */
public final class TokenOverlapCoefficientMatcher extends NamedPythonMatcher {
    public TokenOverlapCoefficientMatcher() { super("overlap_coefficient"); }
}
