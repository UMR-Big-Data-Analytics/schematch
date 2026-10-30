package de.uni_marburg.schematch.matching.similarity.label;

import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matching.TablePairMatcher;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import de.uni_marburg.schematch.similarity.SimilarityMeasure;
import de.uni_marburg.schematch.similarity.string.JaroWinkler;
import de.uni_marburg.schematch.similarity.string.Levenshtein;
import de.uni_marburg.schematch.similarity.string.PrefixSuffix;
import de.uni_marburg.schematch.similarity.string.QGram;

import java.util.Locale;

/**
 * A label matcher whose measure depends on its configured parameters, or on the table pair (for
 * corpus statistics such as IDF). {@link LabelSimilarityMatcher} fixes its measure in the
 * constructor, which is before {@code configure} has set any parameter.
 *
 * <p>Parameters must be declared in the concrete class, because {@code Matcher.configure} looks
 * fields up with {@code getDeclaredField}.
 */
public abstract class ParameterisedLabelMatcher extends TablePairMatcher {
    protected abstract SimilarityMeasure<String> measure(TablePair tablePair);

    @Override
    public float[][] match(TablePair tablePair) {
        Table sourceTable = tablePair.getSourceTable();
        Table targetTable = tablePair.getTargetTable();
        SimilarityMeasure<String> measure = measure(tablePair);
        float[][] simMatrix = tablePair.getEmptySimMatrix();
        for (int i = 0; i < sourceTable.getNumColumns(); i++) {
            for (int j = 0; j < targetTable.getNumColumns(); j++) {
                simMatrix[i][j] = measure.compare(sourceTable.getLabels().get(i), targetTable.getLabels().get(j));
            }
        }
        return simMatrix;
    }

    /** Inner word-level measures shared by the token-based label matchers. */
    public static SimilarityMeasure<String> innerMeasure(String name) {
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "JARO_WINKLER" -> new JaroWinkler();
            case "LEVENSHTEIN" -> new Levenshtein();
            case "PREFIX_SUFFIX" -> (a, b) -> (float) PrefixSuffix.wordSimilarity(a, b);
            case "QGRAM3" -> new QGram(3, QGram.JACCARD, true);
            case "EQUAL" -> (a, b) -> a.equals(b) ? 1f : 0f;
            default -> throw new IllegalArgumentException("Unknown inner measure: " + name);
        };
    }
}
