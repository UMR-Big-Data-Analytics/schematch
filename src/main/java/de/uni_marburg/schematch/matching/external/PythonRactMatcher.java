package de.uni_marburg.schematch.matching.external;

/** RACT column-to-table assignment (MLP with join-path soft labels, then cosine inside the top tables; reimplementation). Standalone: reads the scenario tables and needs no base, so initialMatrix defaults to SERVER. Requires study_server.py (default port 5102). */
public final class PythonRactMatcher extends NamedScenarioPythonMatcher {
    public PythonRactMatcher() {
        super("ract");
        setInitialMatrix("SERVER");
    }
}
