package de.uni_marburg.schematch.matching.external;

/** Bottom-k overlap sketches of HMAC tokens (options.k, default 128). Bundled from the research harness (schematch_py/SOURCES.json). Served by seed_server.py (default port 5101). */
public final class BottomKSketchMatcher extends NamedPythonMatcher {
    public BottomKSketchMatcher() {
        super("bottom_k");
    }
}
