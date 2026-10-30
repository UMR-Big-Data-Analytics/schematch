package de.uni_marburg.schematch.matching.external;

/** Within-table unary dependence role descriptors (options.measure: binary_fd default, gpdep, u, nmi; options.policy: raw, nonkey). BinaryFdRoleMatcher and NmiRoleMatcher fix the measure. Bundled from the research harness (schematch_py/SOURCES.json). Served by seed_server.py (default port 5101). */
public final class DependenceRoleMatcher extends NamedPythonMatcher {
    public DependenceRoleMatcher() {
        super("dependence_role");
    }
}
