package de.uni_marburg.schematch.matching.similarityFlooding;

/**
 * Named preset of {@link SimilarityFlooding}: the standard configuration of the SF study (the thesis's production
 * setting). Schema graph with every projected FD edge (QUICK, fdFilterThreshold 0.0, unweighted), formula C,
 * inverse-product coefficients, the initial matrix on the column-name pairs, no literal similarities, stopping
 * tolerance 1e-4 (sf-matcher-study/tuning/space.py PRODUCTION and BASE_SF). maxIterations is pinned at the default
 * 200; FD edges need FD profiles (readDependencies).
 */
public class SimilarityFloodingStandard extends SimilarityFlooding {
    public SimilarityFloodingStandard() {
        setGraphEncoding("QUICK");
        setPropCoeffPolicy("INV_PROD");
        setFixpoint("C");
        setFdFilterThreshold("0.0");
        setUccFilterThreshold("");
        setIndFilterThreshold("");
        setConstraintWeighting("NONE");
        setDependencyTopK("");
        setFdAllowListFile("");
        setSeedAttachment("NAME");
        setLiteralSeed("NONE");
        setEpsilon("0.0001");
        setMaxIterations("200");
    }
}
