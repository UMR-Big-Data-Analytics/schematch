package de.uni_marburg.schematch.matching.external;

/** Containment of shared-key HMAC tokens, equal to PSI-cardinality containment (options.variant: containment, gcov_sig). Bundled from the research harness (schematch_py/SOURCES.json). Served by seed_server.py (default port 5101). */
public final class HmacContainmentMatcher extends NamedPythonMatcher {
    public HmacContainmentMatcher() {
        super("hmac_containment");
    }
}
