package de.uni_marburg.schematch.matching.external;

/** Sampled value 3-gram soft overlap; distinct from label q-grams. Served by seed_server.py (default port 5101). */
public final class Char3SoftMatcher extends NamedPythonMatcher {
    public Char3SoftMatcher() { super("char3_soft"); }
}
