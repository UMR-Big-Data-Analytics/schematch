package de.uni_marburg.schematch.matching.external;

/** TF-IDF cosine of whole-column value documents. Served by seed_server.py (default port 5101). */
public final class TfIdfValuesMatcher extends NamedPythonMatcher {
    public TfIdfValuesMatcher() { super("tfidf_values"); }
}
