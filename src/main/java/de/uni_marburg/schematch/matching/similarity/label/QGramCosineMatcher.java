package de.uni_marburg.schematch.matching.similarity.label;

import de.uni_marburg.schematch.similarity.string.QGram;

/** Named preset of {@link QGramMatcher} with {@code coefficient=COSINE} (q = 3, padded, unless configured otherwise). */
public final class QGramCosineMatcher extends QGramMatcher {
    public QGramCosineMatcher() {
        setCoefficient(QGram.COSINE);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(q=" + getQ() + ", coefficient=" + getCoefficient() + ", padding=" + getPadding() + ")";
    }
}
