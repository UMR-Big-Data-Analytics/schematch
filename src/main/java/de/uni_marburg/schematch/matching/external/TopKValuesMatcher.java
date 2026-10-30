package de.uni_marburg.schematch.matching.external;

/** Jaccard of the 10 most frequent values. Served by seed_server.py (default port 5101). */
public final class TopKValuesMatcher extends NamedPythonMatcher {
    public TopKValuesMatcher() { super("topk_values"); }
}
