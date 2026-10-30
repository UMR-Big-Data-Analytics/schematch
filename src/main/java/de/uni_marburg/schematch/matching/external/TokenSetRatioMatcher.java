package de.uni_marburg.schematch.matching.external;

/** rapidfuzz token_set_ratio of normalised labels. Served by seed_server.py (default port 5101). */
public final class TokenSetRatioMatcher extends NamedPythonMatcher {
    public TokenSetRatioMatcher() { super("token_set_ratio"); }
}
