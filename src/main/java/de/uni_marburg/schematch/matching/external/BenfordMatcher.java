package de.uni_marburg.schematch.matching.external;

/** 1 - Jensen-Shannon distance of leading-digit distributions. Served by seed_server.py (default port 5101). */
public final class BenfordMatcher extends NamedPythonMatcher {
    public BenfordMatcher() { super("benford"); }
}
