package de.uni_marburg.schematch.matching.external;

/** 1 - total variation distance of value-length histograms. Served by seed_server.py (default port 5101). */
public final class ValueLengthMatcher extends NamedPythonMatcher {
    public ValueLengthMatcher() { super("value_length"); }
}
