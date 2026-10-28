package de.uni_marburg.schematch.matching.external;

/** bdi-kit's contrastive-learning matcher (ct_learning; RoBERTa + SimCLR checkpoint bdi-cl-v0.2), identical embeddings to bdi-kit 0.6.0 without importing it. Needs options.checkpoint / SCHEMATCH_BDIKIT_CL_CHECKPOINT and options.roberta / SCHEMATCH_ROBERTA. Served by seed_server.py (default port 5101). */
public final class BdikitContrastiveLearningMatcher extends NamedPythonMatcher {
    public BdikitContrastiveLearningMatcher() { super("bdikit_cl"); }
}
