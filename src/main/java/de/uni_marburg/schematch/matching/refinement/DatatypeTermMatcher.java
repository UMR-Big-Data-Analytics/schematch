package de.uni_marburg.schematch.matching.refinement;

import de.uni_marburg.schematch.data.Scenario;

/**
 * Datatype term q+ + beta D (rung (a) of the information ladder; Amendment 5's A_d). beta is
 * {@code datatypeWeight}, 5 by default (\dtUniBeta). Reference: sf-matcher-study/reference/score_e2.py
 * additive_rows.
 */
public final class DatatypeTermMatcher extends AdditiveTermMatcher {
    @Override
    double[][] term(float[][] scores, Scenario scenario) { return null; }
}
