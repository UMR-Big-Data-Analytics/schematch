package de.uni_marburg.schematch.matching.external;

/** bdikit TwoPhase (zero-shot Magneto top-k, then COMA); requires the Magneto environment. Served by seed_server.py (default port 5101). */
public final class BdikitTwoPhaseComaMatcher extends NamedPythonMatcher {
    public BdikitTwoPhaseComaMatcher() { super("bdikit_two_phase_coma"); }
}
