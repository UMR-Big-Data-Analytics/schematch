package de.uni_marburg.schematch.matching.similarity.label;

/** Named preset of {@link PhoneticMatcher} with {@code algorithm=NYSIIS}: NYSIIS (Taft 1970) codes of the label tokens, compared by Dice. */
public final class NysiisMatcher extends PhoneticMatcher {
    public NysiisMatcher() {
        setAlgorithm("NYSIIS");
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(algorithm=" + getAlgorithm() + ", normalization=" + getNormalization() + ")";
    }
}
