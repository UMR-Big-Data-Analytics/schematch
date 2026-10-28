package de.uni_marburg.schematch.matching.ensemble;

/** Study (iv) + 0.1 VAL. Preserves additive scale; does not divide by 1.1 or renormalize the two ensembles. */
public final class IVPlusValueMatcher extends NamedCompositeMatcher {
    public IVPlusValueMatcher() {
        setComponents("[{\"name\":\"IVMatcher\",\"package\":\"ensemble\",\"weight\":1},{\"name\":\"ValueAverageMatcher\",\"package\":\"ensemble\",\"weight\":0.1}]");
        setAggregation("SUM");
        setNormalise("NONE");
    }
}
