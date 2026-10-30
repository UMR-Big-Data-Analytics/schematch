package de.uni_marburg.schematch.matching.external;

/** bdikit MaxValSim with zero-shot Magneto top-k; requires the Magneto environment. Served by seed_server.py (default port 5101). */
public final class BdikitMaxValSimMatcher extends NamedPythonMatcher {
    public BdikitMaxValSimMatcher() { super("bdikit_max_val_sim"); }
}
