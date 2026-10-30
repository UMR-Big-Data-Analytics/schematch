package de.uni_marburg.schematch.matching.external;

/** REMA-ARC (Koutras et al., SEAData 2020; reconstruction, no released code): one attribute-row-cell graph over both tables, random walks, skip-gram embeddings, column = mean of its value embeddings, raw cosine. Instance-only (reads no labels); one table per side; needs gensim. Standalone: needs no base, so initialMatrix defaults to SERVER. Requires study_server.py (default port 5102). */
public final class PythonRemaMatcher extends NamedScenarioPythonMatcher {
    public PythonRemaMatcher() {
        super("rema");
        setInitialMatrix("SERVER");
    }
}
