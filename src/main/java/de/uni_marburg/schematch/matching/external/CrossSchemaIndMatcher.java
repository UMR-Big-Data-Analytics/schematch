package de.uni_marburg.schematch.matching.external;

/** Cross-schema IND channels (options.channel: I_g default, E_g, I_raw, E_raw, X). Bundled from the research harness (schematch_py/SOURCES.json). Served by seed_server.py (default port 5101). */
public final class CrossSchemaIndMatcher extends NamedPythonMatcher {
    public CrossSchemaIndMatcher() {
        super("cross_ind");
    }
}
