package de.uni_marburg.schematch.matching.external;

/** Bag-distance similarity (character multisets) of labels. Served by seed_server.py (default port 5101). */
public final class BagDistanceMatcher extends NamedPythonMatcher {
    public BagDistanceMatcher() { super("bag_distance"); }
}
