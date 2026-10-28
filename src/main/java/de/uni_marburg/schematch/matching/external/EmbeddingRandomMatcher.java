package de.uni_marburg.schematch.matching.external;

/** Random embedding control, not a substantive matcher. Served by seed_server.py (default port 5101). */
public final class EmbeddingRandomMatcher extends NamedPythonMatcher {
    public EmbeddingRandomMatcher() { super("embedding_random"); }
}
