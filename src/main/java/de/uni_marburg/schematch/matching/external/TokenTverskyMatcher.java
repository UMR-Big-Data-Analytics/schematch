package de.uni_marburg.schematch.matching.external;

/** Tversky index (alpha = beta = 0.5) of label token sets. Served by seed_server.py (default port 5101). */
public final class TokenTverskyMatcher extends NamedPythonMatcher {
    public TokenTverskyMatcher() { super("tversky"); }
}
