package de.uni_marburg.schematch.matching.external;

/** Sentence-transformer embeddings; model and serialization are set through options. Served by seed_server.py (default port 5101). */
public final class SbertMatcher extends NamedPythonMatcher {
    public SbertMatcher() { super("sbert"); }
}
