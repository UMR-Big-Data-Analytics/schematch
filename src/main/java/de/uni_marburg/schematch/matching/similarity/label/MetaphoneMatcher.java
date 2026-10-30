package de.uni_marburg.schematch.matching.similarity.label;

/** Named preset of {@link PhoneticMatcher} with {@code algorithm=METAPHONE}: Metaphone (Philips 1990) codes of the label tokens, compared by Dice. */
public final class MetaphoneMatcher extends PhoneticMatcher {
    public MetaphoneMatcher() {
        setAlgorithm("METAPHONE");
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(algorithm=" + getAlgorithm() + ", normalization=" + getNormalization() + ")";
    }
}
