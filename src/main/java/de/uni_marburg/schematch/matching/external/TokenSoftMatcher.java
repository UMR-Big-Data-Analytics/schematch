package de.uni_marburg.schematch.matching.external;

/** Sampled value-token soft overlap. Served by seed_server.py (default port 5101). */
public final class TokenSoftMatcher extends NamedPythonMatcher {
    public TokenSoftMatcher() { super("token_soft"); }
}
