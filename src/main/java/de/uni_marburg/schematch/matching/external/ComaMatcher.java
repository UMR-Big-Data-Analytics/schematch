package de.uni_marburg.schematch.matching.external;

/** Valentine COMA schema matcher. Served by seed_server.py (default port 5101). */
public final class ComaMatcher extends NamedPythonMatcher {
    public ComaMatcher() { super("coma"); }
}
