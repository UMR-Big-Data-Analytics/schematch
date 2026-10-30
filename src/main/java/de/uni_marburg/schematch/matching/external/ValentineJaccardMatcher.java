package de.uni_marburg.schematch.matching.external;

/** Valentine Jaccard implementation. Served by seed_server.py (default port 5101). */
public final class ValentineJaccardMatcher extends NamedPythonMatcher {
    public ValentineJaccardMatcher() { super("valentine_jaccard"); }
}
