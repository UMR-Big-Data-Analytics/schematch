package de.uni_marburg.schematch.matching;

import de.uni_marburg.schematch.matching.ensemble.CompositeMatcher;
import de.uni_marburg.schematch.matching.ensemble.CompositeMaxMeanMatcher;
import de.uni_marburg.schematch.matching.ensemble.CompositeMinMatcher;
import de.uni_marburg.schematch.matching.ensemble.CompositeStdevMatcher;
import de.uni_marburg.schematch.matching.ensemble.CompositeWeightedMatcher;
import de.uni_marburg.schematch.matching.similarity.label.ColognePhoneticMatcher;
import de.uni_marburg.schematch.matching.similarity.label.MetaphoneMatcher;
import de.uni_marburg.schematch.matching.similarity.label.NysiisMatcher;
import de.uni_marburg.schematch.matching.similarity.label.QGramCosineMatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The named presets of existing matcher options set exactly the option they are named for. */
class NamedPresetsTest {
    @Test
    void phoneticAndQGramPresetsSetTheirOption() {
        assertEquals("METAPHONE", new MetaphoneMatcher().getAlgorithm());
        assertEquals("NYSIIS", new NysiisMatcher().getAlgorithm());
        assertEquals("COLOGNE", new ColognePhoneticMatcher().getAlgorithm());
        assertEquals("COSINE", new QGramCosineMatcher().getCoefficient());
        assertEquals(3, new QGramCosineMatcher().getQ());
    }

    @Test
    void compositePresetsParseTheStudyComponents() {
        for (CompositeMatcher m : new CompositeMatcher[]{new CompositeMinMatcher(), new CompositeWeightedMatcher(),
                new CompositeStdevMatcher(), new CompositeMaxMeanMatcher()}) {
            assertEquals(5, CompositeMatcher.components(m.getComponents()).size(), m.toString());
        }
        assertEquals("MIN", new CompositeMinMatcher().getAggregation());
        assertEquals("WEIGHTED", new CompositeWeightedMatcher().getAggregation());
        assertEquals("PREDICTOR", new CompositeStdevMatcher().getAggregation());
        assertEquals("STDEV", new CompositeStdevMatcher().getPredictor());
        assertEquals("MAXMEAN", new CompositeMaxMeanMatcher().getPredictor());
    }
}
