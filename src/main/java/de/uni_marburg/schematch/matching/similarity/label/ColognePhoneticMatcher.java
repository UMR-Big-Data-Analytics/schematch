package de.uni_marburg.schematch.matching.similarity.label;

/** Named preset of {@link PhoneticMatcher} with {@code algorithm=COLOGNE}: Cologne phonetics (Postel 1969, for German labels) codes of the label tokens, compared by Dice. */
public final class ColognePhoneticMatcher extends PhoneticMatcher {
    public ColognePhoneticMatcher() {
        setAlgorithm("COLOGNE");
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(algorithm=" + getAlgorithm() + ", normalization=" + getNormalization() + ")";
    }
}
