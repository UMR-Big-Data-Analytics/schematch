package de.uni_marburg.schematch.matching.ensemble;

/** Study (iv): unit-mean of COMA, value char3_soft, Cupid, and SBERT MPNet. Requires seed_server.py. */
public final class IVMatcher extends NamedCompositeMatcher {
    public IVMatcher() {
        setComponents("[{\"name\":\"ComaMatcher\",\"package\":\"external\"},{\"name\":\"Char3SoftMatcher\",\"package\":\"external\"},{\"name\":\"CupidMatcher\",\"package\":\"external\"},{\"name\":\"SbertMatcher\",\"package\":\"external\",\"params\":{\"options\":\"{\\\"model\\\":\\\"all-mpnet-base-v2\\\"}\"}}]");
        setAggregation("AVERAGE");
        setNormalise("MINMAX");
    }
}
