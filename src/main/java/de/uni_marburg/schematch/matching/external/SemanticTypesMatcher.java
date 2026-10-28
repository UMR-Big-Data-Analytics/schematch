package de.uni_marburg.schematch.matching.external;

/** Semantic-type adapter; options must identify a configured adapter. Served by seed_server.py (default port 5101). */
public final class SemanticTypesMatcher extends NamedPythonMatcher {
    public SemanticTypesMatcher() { super("semantic_types"); }
}
