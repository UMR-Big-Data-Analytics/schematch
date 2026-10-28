package de.uni_marburg.schematch.matching.external;

/** Registered-study solver/order/tie preset. Supplying options replaces the entire JSON object. Requires study_server.py and SciPy MILP. */
public final class StudyReplayEntityAlignmentMatcher extends NamedScenarioPythonMatcher {
    public StudyReplayEntityAlignmentMatcher() {
        super("entity_alignment");
        setOptions("{\"greedy\":false,\"canonical\":false,\"tie_noise\":0}");
    }
}
