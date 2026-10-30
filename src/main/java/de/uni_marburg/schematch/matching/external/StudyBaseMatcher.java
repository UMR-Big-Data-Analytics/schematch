package de.uni_marburg.schematch.matching.external;

/** Study base composition/replay; SERVER mode needs seed_root and base_recipe. Requires study_server.py (default port 5102). */
public final class StudyBaseMatcher extends NamedScenarioPythonMatcher {
    public StudyBaseMatcher() { super("base"); }
}
