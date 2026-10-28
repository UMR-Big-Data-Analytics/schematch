package de.uni_marburg.schematch.matching.ensemble;

/** Named preset of {@link CompositeMatcher}: COMA's MIN aggregation of the study's composite components (Levenshtein, prefix/suffix, WordNet, containment, distribution; sf-matcher-study make_configs COMPOSITE). */
public final class CompositeMinMatcher extends NamedCompositeMatcher {
    public CompositeMinMatcher() {
        setComponents("[{\"name\":\"LevenshteinMatcher\",\"package\":\"similarity.label\"},{\"name\":\"PrefixSuffixMatcher\",\"package\":\"similarity.label\"},{\"name\":\"WordNetMatcher\",\"package\":\"similarity.label\"},{\"name\":\"ContainmentMatcher\",\"package\":\"similarity.instance\"},{\"name\":\"DistributionMatcher\",\"package\":\"similarity.instance\"}]");
        setAggregation("MIN");
    }
}
