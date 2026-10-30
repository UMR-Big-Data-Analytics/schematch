package de.uni_marburg.schematch.matching.external;

/** Within-table inclusion role profiles with significance. Served by seed_server.py (default port 5101). */
public final class RoleGcovSignificanceMatcher extends NamedPythonMatcher {
    public RoleGcovSignificanceMatcher() { super("role_gcov_sig"); }
}
