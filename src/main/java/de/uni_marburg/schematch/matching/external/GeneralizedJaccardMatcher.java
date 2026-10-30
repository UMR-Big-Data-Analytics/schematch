package de.uni_marburg.schematch.matching.external;

/** Generalized Jaccard of label tokens (Jaro-Winkler >= 0.8, one-to-one). Served by seed_server.py (default port 5101). */
public final class GeneralizedJaccardMatcher extends NamedPythonMatcher {
    public GeneralizedJaccardMatcher() { super("generalized_jaccard"); }
}
