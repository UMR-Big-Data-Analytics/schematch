package de.uni_marburg.schematch.matching.external;

/** exp(-energy distance / pooled std) of numeric columns. Served by seed_server.py (default port 5101). */
public final class EnergyDistanceMatcher extends NamedPythonMatcher {
    public EnergyDistanceMatcher() { super("energy_distance"); }
}
