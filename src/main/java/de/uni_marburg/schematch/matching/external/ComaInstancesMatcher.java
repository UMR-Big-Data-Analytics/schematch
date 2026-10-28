package de.uni_marburg.schematch.matching.external;

/** Valentine COMA schema-and-instance variant. Served by seed_server.py (default port 5101). */
public final class ComaInstancesMatcher extends NamedPythonMatcher {
    public ComaInstancesMatcher() { super("coma_instances"); }
}
