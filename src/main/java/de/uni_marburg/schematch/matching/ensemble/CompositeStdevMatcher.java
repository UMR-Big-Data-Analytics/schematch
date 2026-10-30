package de.uni_marburg.schematch.matching.ensemble;

/** Named preset of {@link CompositeMatcher}: quality-predictor weighting (Sagi & Gal 2013) by each component matrix's standard deviation. */
public final class CompositeStdevMatcher extends NamedCompositeMatcher {
    public CompositeStdevMatcher() {
        setComponents("[{\"name\":\"LevenshteinMatcher\",\"package\":\"similarity.label\"},{\"name\":\"PrefixSuffixMatcher\",\"package\":\"similarity.label\"},{\"name\":\"WordNetMatcher\",\"package\":\"similarity.label\"},{\"name\":\"ContainmentMatcher\",\"package\":\"similarity.instance\"},{\"name\":\"DistributionMatcher\",\"package\":\"similarity.instance\"}]");
        setAggregation("PREDICTOR");
        setPredictor("STDEV");
    }
}
