package de.uni_marburg.schematch.matching.ensemble;

/** VAL: mean of separately min-max-normalized invariant unary statistics, PMF, and unary statistics. */
public final class ValueAverageMatcher extends NamedCompositeMatcher {
    public ValueAverageMatcher() {
        setComponents("[{\"name\":\"UnaryStatisticsMatcher\",\"package\":\"metadata\",\"params\":{\"recodingInvariant\":true}},{\"name\":\"PMFMatcher\",\"package\":\"similarity.instance\"},{\"name\":\"UnaryStatisticsMatcher\",\"package\":\"metadata\"}]");
        setAggregation("AVERAGE");
        setNormalise("MINMAX");
    }
}
