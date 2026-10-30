package de.uni_marburg.schematch.matching.external;

/** bdikit TwoPhase (zero-shot Magneto top-k, then SimFlood); requires the Magneto environment. Served by seed_server.py (default port 5101). */
public final class BdikitTwoPhaseMatcher extends NamedPythonMatcher {
    public BdikitTwoPhaseMatcher() { super("bdikit_two_phase"); }
}
