package de.uni_marburg.schematch.similarity.string;

import de.uni_marburg.schematch.similarity.SimilarityMeasure;

import java.util.Locale;

/**
 * SMOA string metric for ontology alignment (Stoilos, Stamou &amp; Kollias, ISWC 2005):
 * {@code Sim = Comm - Diff + Winkler}.
 *
 * <ul>
 *     <li>Comm: the longest common substring is removed from both strings repeatedly while it is at
 *     least {@code minSubstring} characters long; {@code Comm = 2 * sum(lengths) / (|s1| + |s2|)}.</li>
 *     <li>Diff: Hamacher product of the unmatched fractions u1, u2 with p = 0.6,
 *     {@code u1*u2 / (p + (1-p)(u1 + u2 - u1*u2))}.</li>
 *     <li>Winkler: common-prefix improvement {@code min(prefix, 4) * 0.1 * (1 - Comm)}.</li>
 * </ul>
 * The metric lies in [-1, 1] and is mapped to [0, 1] by {@code (sim + 1) / 2}. As in the paper,
 * strings are lower-cased and the separators '.', '_' and ' ' are removed first. The minimum
 * substring length of 3 follows the Alignment API implementation; it is a parameter.
 */
public class Smoa implements SimilarityMeasure<String> {
    private static final double P = 0.6;
    private final int minSubstring;

    public Smoa() {
        this(3);
    }

    public Smoa(int minSubstring) {
        this.minSubstring = Math.max(1, minSubstring);
    }

    static String normalise(String s) {
        return s.toLowerCase(Locale.ROOT).replace(".", "").replace("_", "").replace(" ", "");
    }

    public double raw(String s1, String s2) {
        String a = normalise(s1);
        String b = normalise(s2);
        int l1 = a.length();
        int l2 = b.length();
        if (l1 == 0 && l2 == 0) {
            return 1.0;
        }
        if (l1 == 0 || l2 == 0) {
            return -1.0;
        }
        int prefix = 0;
        while (prefix < Math.min(Math.min(l1, l2), 4) && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }

        StringBuilder x = new StringBuilder(a);
        StringBuilder y = new StringBuilder(b);
        int common = 0;
        while (true) {
            int[] best = longestCommonSubstring(x, y);
            if (best[2] < minSubstring) {
                break;
            }
            x.delete(best[0], best[0] + best[2]);
            y.delete(best[1], best[1] + best[2]);
            common += best[2];
        }
        double comm = 2.0 * common / (l1 + l2);
        double u1 = (double) (l1 - common) / l1;
        double u2 = (double) (l2 - common) / l2;
        double diff = (u1 * u2) / (P + (1 - P) * (u1 + u2 - u1 * u2));
        double winkler = prefix * 0.1 * (1 - comm);
        return comm - diff + winkler;
    }

    /** {start in x, start in y, length} of a longest common substring (first found on ties). */
    static int[] longestCommonSubstring(CharSequence x, CharSequence y) {
        int[] best = {0, 0, 0};
        int[] prev = new int[y.length() + 1];
        int[] curr = new int[y.length() + 1];
        for (int i = 1; i <= x.length(); i++) {
            for (int j = 1; j <= y.length(); j++) {
                if (x.charAt(i - 1) == y.charAt(j - 1)) {
                    curr[j] = prev[j - 1] + 1;
                    if (curr[j] > best[2]) {
                        best[0] = i - curr[j];
                        best[1] = j - curr[j];
                        best[2] = curr[j];
                    }
                } else {
                    curr[j] = 0;
                }
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
            java.util.Arrays.fill(curr, 0);
        }
        return best;
    }

    @Override
    public float compare(String s1, String s2) {
        double v = (raw(s1, s2) + 1.0) / 2.0;
        return (float) Math.max(0.0, Math.min(1.0, v));
    }
}
