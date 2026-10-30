package de.uni_marburg.schematch.matching.external;

/** Order role: quantiles of within-table non-discordance over sampled row pairs, cosine (options.seed). Bundled from the research harness (schematch_py/SOURCES.json). Served by seed_server.py (default port 5101). */
public final class OrderRoleMatcher extends NamedPythonMatcher {
    public OrderRoleMatcher() {
        super("order_role");
    }
}
