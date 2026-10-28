package de.uni_marburg.schematch.matching.external;

/** ALITE holistic matching (constrained agglomerative clustering of column embeddings; reimplementation, mpnet instead of TURL/fastText). Standalone: reads the scenario tables and needs no base, so initialMatrix defaults to SERVER. Requires study_server.py (default port 5102). */
public final class PythonAliteMatcher extends NamedScenarioPythonMatcher {
    public PythonAliteMatcher() {
        super("alite");
        setInitialMatrix("SERVER");
    }
}
