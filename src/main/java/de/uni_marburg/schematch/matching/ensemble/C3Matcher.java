package de.uni_marburg.schematch.matching.ensemble;

/** Study C3: mean(unit(char3_soft), unit(jsd_values), VAL). Does not normalize VAL a second time. */
public final class C3Matcher extends NamedCompositeMatcher {
    public C3Matcher() {
        setComponents("[{\"name\":\"CompositeMatcher\",\"package\":\"ensemble\",\"params\":{\"components\":\"[{\\\"name\\\":\\\"Char3SoftMatcher\\\",\\\"package\\\":\\\"external\\\"}]\",\"normalise\":\"MINMAX\"}},{\"name\":\"CompositeMatcher\",\"package\":\"ensemble\",\"params\":{\"components\":\"[{\\\"name\\\":\\\"JensenShannonValueMatcher\\\",\\\"package\\\":\\\"similarity.instance\\\"}]\",\"normalise\":\"MINMAX\"}},{\"name\":\"ValueAverageMatcher\",\"package\":\"ensemble\"}]");
        setAggregation("AVERAGE");
        setNormalise("NONE");
    }
}
