package de.uni_marburg.schematch.matching.external;

/** Unary-key role: agreement of single-column UCC membership (UCC_results.txt of each table). Bundled from the research harness (schematch_py/SOURCES.json). Served by seed_server.py (default port 5101). */
public final class UnaryKeyRoleMatcher extends NamedPythonMatcher {
    public UnaryKeyRoleMatcher() {
        super("unary_key_role");
    }
}
