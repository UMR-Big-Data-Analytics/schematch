package de.uni_marburg.schematch.matching.external;

/** X109 scenario entity alignment; defaults to greedy solver, canonical ordering and seeded tie noise. Requires study_server.py (default port 5102). */
public final class EntityAlignmentMatcher extends NamedScenarioPythonMatcher {
    public EntityAlignmentMatcher() { super("entity_alignment"); }
}
