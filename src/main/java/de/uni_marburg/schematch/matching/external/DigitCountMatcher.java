package de.uni_marburg.schematch.matching.external;

/** 1 - Jensen-Shannon distance of per-value digit-count distributions. Served by seed_server.py (default port 5101). */
public final class DigitCountMatcher extends NamedPythonMatcher {
    public DigitCountMatcher() { super("digit_count"); }
}
