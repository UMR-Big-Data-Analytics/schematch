package de.uni_marburg.schematch.matching.external;

/** Denial-constraint role: degree, mean length and equality share over the table's DCs, cosine (options.source: lima default, dcfinder). Bundled from the research harness (schematch_py/SOURCES.json). Served by seed_server.py (default port 5101). */
public final class DcRoleMatcher extends NamedPythonMatcher {
    public DcRoleMatcher() {
        super("dc_role");
    }
}
