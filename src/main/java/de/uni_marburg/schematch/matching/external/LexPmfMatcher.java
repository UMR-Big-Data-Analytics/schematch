package de.uni_marburg.schematch.matching.external;

/** LEX+PMF: the harness's frozen initial matcher, scale-aligned 0.65 Levenshtein + 0.35 PMF (options.weight); Python port of the Java Levenshtein. Bundled from the research harness (schematch_py/SOURCES.json). Served by seed_server.py (default port 5101). */
public final class LexPmfMatcher extends NamedPythonMatcher {
    public LexPmfMatcher() {
        super("lex_pmf");
    }
}
