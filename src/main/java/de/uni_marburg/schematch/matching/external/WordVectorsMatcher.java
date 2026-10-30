package de.uni_marburg.schematch.matching.external;

/** Word-vector similarity; options must provide the vector resource. Served by seed_server.py (default port 5101). */
public final class WordVectorsMatcher extends NamedPythonMatcher {
    public WordVectorsMatcher() { super("word_vectors"); }
}
