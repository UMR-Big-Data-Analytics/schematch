package de.uni_marburg.schematch.matching.external;

/** The project's Kang–Naughton MI-graph reconstruction, not an upstream implementation. Served by seed_server.py (default port 5101). */
public final class KangNaughtonMatcher extends NamedPythonMatcher {
    public KangNaughtonMatcher() { super("kang_naughton"); }
}
