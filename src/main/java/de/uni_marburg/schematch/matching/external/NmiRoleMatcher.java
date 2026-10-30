package de.uni_marburg.schematch.matching.external;

/** Within-table normalised mutual information role descriptors (options.policy: raw, nonkey). Bundled from the research harness (schematch_py/SOURCES.json). Served by seed_server.py (default port 5101). */
public final class NmiRoleMatcher extends NamedPythonMatcher {
    public NmiRoleMatcher() {
        super("nmi_role");
    }
}
