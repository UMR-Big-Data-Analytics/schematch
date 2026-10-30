package de.uni_marburg.schematch.matching.external;

/** Within-table chance-corrected inclusion role profiles. Served by seed_server.py (default port 5101). */
public final class RoleGcovMatcher extends NamedPythonMatcher {
    public RoleGcovMatcher() { super("role_gcov"); }
}
