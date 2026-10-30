package de.uni_marburg.schematch.matching.external;

/** Ratcliff-Obershelp (difflib) similarity of normalised labels. Served by seed_server.py (default port 5101). */
public final class RatcliffObershelpMatcher extends NamedPythonMatcher {
    public RatcliffObershelpMatcher() { super("ratcliff_obershelp"); }
}
