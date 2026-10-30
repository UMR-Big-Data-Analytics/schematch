package de.uni_marburg.schematch.matching.external;

/** rapidfuzz token_sort_ratio of normalised labels. Served by seed_server.py (default port 5101). */
public final class TokenSortRatioMatcher extends NamedPythonMatcher {
    public TokenSortRatioMatcher() { super("token_sort_ratio"); }
}
