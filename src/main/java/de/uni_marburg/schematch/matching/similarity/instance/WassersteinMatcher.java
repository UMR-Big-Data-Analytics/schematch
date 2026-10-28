package de.uni_marburg.schematch.matching.similarity.instance;

/** Normalized empirical Wasserstein similarity, using the inherited domain setting. */
public final class WassersteinMatcher extends DistributionTestMatcher {
    public WassersteinMatcher() { setTest("WASSERSTEIN"); setDomain("AUTO"); }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(test=" + getTest() + ", domain=" + getDomain() + ", bins=" + getBins() + ")";
    }
}
