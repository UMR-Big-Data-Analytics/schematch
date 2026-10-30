package de.uni_marburg.schematch.matching.external;

/** Bhattacharyya coefficient of numeric values on a shared 20-bin grid. Served by seed_server.py (default port 5101). */
public final class BhattacharyyaMatcher extends NamedPythonMatcher {
    public BhattacharyyaMatcher() { super("bhattacharyya"); }
}
