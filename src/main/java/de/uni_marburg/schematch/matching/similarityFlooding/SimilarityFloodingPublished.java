package de.uni_marburg.schematch.matching.similarityFlooding;

/**
 * Named preset of {@link SimilarityFlooding}: SF as published (Melnik et al., ICDE 2002), the reference configuration
 * of the SF study. Schema graph without dependency edges (QUICK, no dependency thresholds), formula C, inverse-average
 * coefficients, stop when the Euclidean residual falls below 0.05; the initial matrix on the column-name pairs (NAME)
 * and the prefix/suffix string similarity on every other pair of non-ID nodes (StringMatch, PREFIX_SUFFIX). Values as in
 * sf-matcher-study/reference/make_layer1_configs.py (MELNIK_SF, MELNIK_PROPS). maxIterations is pinned at the
 * default 200; other knobs keep their parameter or property fallbacks, and every value can still be overridden.
 */
public class SimilarityFloodingPublished extends SimilarityFlooding {
    public SimilarityFloodingPublished() {
        apply(this);
    }

    static void apply(SimilarityFlooding sf) {
        sf.setGraphEncoding("QUICK");
        sf.setPropCoeffPolicy("INV_AVG");
        sf.setFixpoint("C");
        sf.setFdFilterThreshold("");
        sf.setUccFilterThreshold("");
        sf.setIndFilterThreshold("");
        sf.setConstraintWeighting("NONE");
        sf.setDependencyTopK("");
        sf.setFdAllowListFile("");
        sf.setSeedAttachment("NAME");
        sf.setLiteralSeed("PREFIX_SUFFIX");
        sf.setEpsilon("0.05");
        sf.setMaxIterations("200");
    }
}
