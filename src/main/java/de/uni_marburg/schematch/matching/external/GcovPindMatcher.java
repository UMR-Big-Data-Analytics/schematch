package de.uni_marburg.schematch.matching.external;

/** Cross-schema partial-IND coverage, chance-corrected in both directions against the scenario's value pool (options.variant: gcov_both, gcov, cov, both, both_card). Bundled from the research harness (schematch_py/SOURCES.json). Served by seed_server.py (default port 5101). */
public final class GcovPindMatcher extends NamedPythonMatcher {
    public GcovPindMatcher() {
        super("gcov_pind");
    }
}
