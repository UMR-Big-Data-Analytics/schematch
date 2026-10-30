package de.uni_marburg.schematch.matching.external;

/** X105 scenario refinement using entities, references and relaxed FD signatures. Requires study_server.py (default port 5102). */
public final class DependencyMatcher extends NamedScenarioPythonMatcher {
    public DependencyMatcher() { super("dep"); }
}
