package de.uni_marburg.schematch.matching.similarity.instance;

/** Jensen–Shannon similarity over shared value identities, distinct from sorted PMF. */
public final class JensenShannonValueMatcher extends DistributionTestMatcher {
    public JensenShannonValueMatcher() { setTest("JSD"); setDomain("VALUE"); }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(test=" + getTest() + ", domain=" + getDomain() + ", bins=" + getBins() + ")";
    }
}
