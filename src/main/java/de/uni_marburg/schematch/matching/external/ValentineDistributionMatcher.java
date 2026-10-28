package de.uni_marburg.schematch.matching.external;

/** Valentine distribution matcher, including its clustering; not sorted PMF. Served by seed_server.py (default port 5101). */
public final class ValentineDistributionMatcher extends NamedPythonMatcher {
    public ValentineDistributionMatcher() { super("valentine_distribution"); }
}
