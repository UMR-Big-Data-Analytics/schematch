package de.uni_marburg.schematch.matching.similarity.instance;

/** Two-sample KS statistic as similarity, using the inherited domain setting. */
public final class KolmogorovSmirnovMatcher extends DistributionTestMatcher {
    public KolmogorovSmirnovMatcher() { setTest("KS"); setDomain("AUTO"); }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(test=" + getTest() + ", domain=" + getDomain() + ", bins=" + getBins() + ")";
    }
}
