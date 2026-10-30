package de.uni_marburg.schematch.matching.external;

/** Within-table AFD role descriptors (options.measure: gpdep default, tau, mu_plus, g3n, rfi_plus; options.policy: raw, nonkey). Bundled from the research harness (schematch_py/SOURCES.json). Served by seed_server.py (default port 5101). */
public final class AfdRoleMatcher extends NamedPythonMatcher {
    public AfdRoleMatcher() {
        super("afd_role");
    }
}
