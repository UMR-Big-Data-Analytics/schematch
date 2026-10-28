package de.uni_marburg.schematch.matching.external;

/** Abbreviation-aware names: common-prefix stripping, ordered token prefixes, consonant skeletons. Served by seed_server.py (default port 5101). */
public final class AbbreviationMatcher extends NamedPythonMatcher {
    public AbbreviationMatcher() { super("abbreviation"); }
}
