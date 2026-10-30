package de.uni_marburg.schematch.matching.external;

/** Exact solver with the server's canonical/tie-noise defaults; not registered-study replay. Requires study_server.py and SciPy MILP. */
public final class ExactEntityAlignmentMatcher extends NamedScenarioPythonMatcher {
    public ExactEntityAlignmentMatcher() {
        super("entity_alignment");
        setOptions("{\"greedy\":false}");
    }
}
