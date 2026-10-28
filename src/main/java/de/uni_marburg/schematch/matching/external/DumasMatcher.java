package de.uni_marburg.schematch.matching.external;

/** DUMAS (Bilke and Naumann 2005), simplified: field similarity over the most similar record pairs. Served by seed_server.py (default port 5101). */
public final class DumasMatcher extends NamedPythonMatcher {
    public DumasMatcher() { super("dumas"); }
}
