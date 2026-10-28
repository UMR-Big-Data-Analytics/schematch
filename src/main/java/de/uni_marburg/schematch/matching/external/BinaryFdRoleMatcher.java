package de.uni_marburg.schematch.matching.external;

/** Within-table exact unary FD role descriptors (options.policy: raw, nonkey). Bundled from the research harness (schematch_py/SOURCES.json). Served by seed_server.py (default port 5101). */
public final class BinaryFdRoleMatcher extends NamedPythonMatcher {
    public BinaryFdRoleMatcher() {
        super("binary_fd_role");
    }
}
