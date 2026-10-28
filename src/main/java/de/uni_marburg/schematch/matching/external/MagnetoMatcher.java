package de.uni_marburg.schematch.matching.external;

/** Magneto adapter; requires the seed server's compatible model environment. Served by seed_server.py (default port 5101). */
public final class MagnetoMatcher extends NamedPythonMatcher {
    public MagnetoMatcher() { super("magneto"); }
}
