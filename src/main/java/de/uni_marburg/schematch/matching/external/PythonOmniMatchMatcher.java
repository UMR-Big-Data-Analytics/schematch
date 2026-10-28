package de.uni_marburg.schematch.matching.external;

/** OmniMatch (self-supervised RGCN over five column-similarity relations; reimplementation, mpnet instead of fastText). Standalone: reads the scenario tables and needs no base, so initialMatrix defaults to SERVER. Requires study_server.py (default port 5102). */
public final class PythonOmniMatchMatcher extends NamedScenarioPythonMatcher {
    public PythonOmniMatchMatcher() {
        super("omnimatch");
        setInitialMatrix("SERVER");
    }
}
