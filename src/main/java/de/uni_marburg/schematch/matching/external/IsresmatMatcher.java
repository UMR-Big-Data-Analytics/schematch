package de.uni_marburg.schematch.matching.external;

/** ISResMat subprocess adapter; requires its separately configured environment. Served by seed_server.py (default port 5101). */
public final class IsresmatMatcher extends NamedPythonMatcher {
    public IsresmatMatcher() { super("isresmat"); }
}
