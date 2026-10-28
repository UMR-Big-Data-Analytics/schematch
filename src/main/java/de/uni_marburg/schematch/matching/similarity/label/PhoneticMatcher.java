package de.uni_marburg.schematch.matching.similarity.label;

import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import de.uni_marburg.schematch.preprocessing.LabelNormalizer;
import de.uni_marburg.schematch.similarity.SimilarityMeasure;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.commons.codec.StringEncoder;
import org.apache.commons.codec.language.ColognePhonetic;
import org.apache.commons.codec.language.DoubleMetaphone;
import org.apache.commons.codec.language.Metaphone;
import org.apache.commons.codec.language.Nysiis;
import org.apache.commons.codec.language.Soundex;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Phonetic label similarity: each label token is replaced by its phonetic code and the two code
 * sets are compared by Dice. Tokens that are not purely alphabetic (numbers) are kept verbatim.
 *
 * <p>{@code algorithm}: SOUNDEX (Russell 1918), METAPHONE (Philips 1990), DOUBLE_METAPHONE
 * (Philips 2000, default), NYSIIS (Taft 1970) or COLOGNE (Postel 1969, for German labels).
 * Encoders are Apache Commons Codec's.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PhoneticMatcher extends ParameterisedLabelMatcher {
    private String algorithm = "DOUBLE_METAPHONE";
    private String normalization = LabelNormalizer.SPLIT;

    static StringEncoder encoder(String name) {
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "SOUNDEX" -> new Soundex();
            case "METAPHONE" -> new Metaphone();
            case "DOUBLE_METAPHONE" -> new DoubleMetaphone();
            case "NYSIIS" -> new Nysiis();
            case "COLOGNE" -> new ColognePhonetic();
            default -> throw new IllegalArgumentException("Unknown phonetic algorithm: " + name);
        };
    }

    Set<String> codes(String label, StringEncoder enc) {
        List<String> tokens = LabelNormalizer.tokens(label, normalization);
        Set<String> out = new HashSet<>();
        for (String t : tokens) {
            if (!t.chars().allMatch(Character::isLetter)) {
                out.add("#" + t);
                continue;
            }
            try {
                String code = enc.encode(t);
                out.add(code == null || code.isEmpty() ? "#" + t : code);
            } catch (Exception e) {
                out.add("#" + t);
            }
        }
        return out;
    }

    @Override
    protected SimilarityMeasure<String> measure(TablePair tablePair) {
        StringEncoder enc = encoder(algorithm);
        return (a, b) -> {
            Set<String> x = codes(a, enc);
            Set<String> y = codes(b, enc);
            if (x.isEmpty() || y.isEmpty()) {
                return 0f;
            }
            int inter = 0;
            for (String c : x) {
                if (y.contains(c)) {
                    inter++;
                }
            }
            return (float) (2.0 * inter / (x.size() + y.size()));
        };
    }
}
