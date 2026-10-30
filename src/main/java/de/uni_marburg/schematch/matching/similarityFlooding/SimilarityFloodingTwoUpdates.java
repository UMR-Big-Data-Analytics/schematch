package de.uni_marburg.schematch.matching.similarityFlooding;

/**
 * Named preset of {@link SimilarityFlooding}: SF as published ({@link SimilarityFloodingPublished}) stopped after two
 * updates. The fixpoint loop performs maxIterations + 1 updates, so two updates are maxIterations = 1, as in the
 * study's iteration arms (sf-matcher-study/servers/make_iter_configs.py, make_x95_configs.py "_t2"). A run may stop
 * earlier when the residual already falls below 0.05.
 */
public class SimilarityFloodingTwoUpdates extends SimilarityFlooding {
    public SimilarityFloodingTwoUpdates() {
        SimilarityFloodingPublished.apply(this);
        setMaxIterations("1");
    }
}
