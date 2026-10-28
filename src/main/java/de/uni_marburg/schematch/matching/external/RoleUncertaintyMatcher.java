package de.uni_marburg.schematch.matching.external;

/** Within-table conditional-uncertainty role profiles. Served by seed_server.py (default port 5101). */
public final class RoleUncertaintyMatcher extends NamedPythonMatcher {
    public RoleUncertaintyMatcher() { super("role_uncertainty"); }
}
