package de.uni_marburg.schematch.matching.external;

/** OmniMatch ported from the authors' release (amazon-science/omnimatch, CC BY-NC 4.0): self-generated join pairs, five feature relations, top-3 graph, RGCN with triplet loss; needs fastText cc.en.300 (options.vectors / SCHEMATCH_FASTTEXT). Standalone: needs no base, so initialMatrix defaults to SERVER. Requires study_server.py (default port 5102). */
public final class PythonOmniMatchReleaseMatcher extends NamedScenarioPythonMatcher {
    public PythonOmniMatchReleaseMatcher() {
        super("omnimatch_release");
        setInitialMatrix("SERVER");
    }
}
