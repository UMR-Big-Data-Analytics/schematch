package de.uni_marburg.schematch.matching.ensemble;

/** Named preset of {@link CompositeMatcher}: COMA's WEIGHTED aggregation (the components' weights) of the study's composite components (Levenshtein, prefix/suffix, WordNet, containment, distribution; sf-matcher-study make_configs COMPOSITE). Every component has weight 1 unless
 * {@code components} is overridden with weights, so the default equals AVERAGE. */
public final class CompositeWeightedMatcher extends NamedCompositeMatcher {
    public CompositeWeightedMatcher() {
        setComponents("[{\"name\":\"LevenshteinMatcher\",\"package\":\"similarity.label\"},{\"name\":\"PrefixSuffixMatcher\",\"package\":\"similarity.label\"},{\"name\":\"WordNetMatcher\",\"package\":\"similarity.label\"},{\"name\":\"ContainmentMatcher\",\"package\":\"similarity.instance\"},{\"name\":\"DistributionMatcher\",\"package\":\"similarity.instance\"}]");
        setAggregation("WEIGHTED");
    }
}
