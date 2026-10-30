package de.uni_marburg.schematch.similarity.string;

import de.uni_marburg.schematch.similarity.SimilarityMeasure;

import java.util.Locale;

/**
 * Sequence-alignment string similarities, case-insensitive, with the SimMetrics library's default
 * costs and normalisations (Chapman's SimMetrics, the reference implementation set used in string
 * matching surveys):
 *
 * <ul>
 *     <li>NEEDLEMAN_WUNSCH: global alignment (Needleman &amp; Wunsch 1970) as an edit cost,
 *     substitution 1, gap 2; {@code 1 - cost / (max(|a|,|b|) * 2)}.</li>
 *     <li>SMITH_WATERMAN: local alignment (Smith &amp; Waterman 1981), match +1, mismatch -2,
 *     linear gap -0.5; {@code score / min(|a|,|b|)}.</li>
 *     <li>SMITH_WATERMAN_GOTOH: local alignment with affine gaps (Gotoh 1982), match +1,
 *     mismatch -2, gap open -1, gap extend -0.25, so a long run of skipped letters costs less than
 *     the same number of scattered gaps; normalised as SMITH_WATERMAN.</li>
 * </ul>
 */
public class Alignment implements SimilarityMeasure<String> {
    public static final String SMITH_WATERMAN = "SMITH_WATERMAN";
    public static final String SMITH_WATERMAN_GOTOH = "SMITH_WATERMAN_GOTOH";
    public static final String NEEDLEMAN_WUNSCH = "NEEDLEMAN_WUNSCH";

    private static final double MATCH = 1.0;
    private static final double MISMATCH = -2.0;
    private static final double GAP = -0.5;
    private static final double GAP_OPEN = -1.0;
    private static final double GAP_EXTEND = -0.25;
    private static final double NW_GAP_COST = 2.0;
    private static final double NW_SUBSTITUTION_COST = 1.0;

    private final String algorithm;

    public Alignment(String algorithm) {
        String a = algorithm.toUpperCase(Locale.ROOT);
        if (!a.equals(SMITH_WATERMAN) && !a.equals(SMITH_WATERMAN_GOTOH) && !a.equals(NEEDLEMAN_WUNSCH)) {
            throw new IllegalArgumentException("Unknown alignment algorithm: " + algorithm);
        }
        this.algorithm = a;
    }

    private static double sub(char x, char y) {
        return x == y ? MATCH : MISMATCH;
    }

    static double smithWaterman(String a, String b) {
        double[][] h = new double[a.length() + 1][b.length() + 1];
        double best = 0;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                double v = Math.max(0, h[i - 1][j - 1] + sub(a.charAt(i - 1), b.charAt(j - 1)));
                v = Math.max(v, h[i - 1][j] + GAP);
                v = Math.max(v, h[i][j - 1] + GAP);
                h[i][j] = v;
                best = Math.max(best, v);
            }
        }
        return best;
    }

    /** Local alignment with affine gaps: H (match), E (gap in a), F (gap in b). */
    static double smithWatermanGotoh(String a, String b) {
        int n = a.length();
        int m = b.length();
        double[][] h = new double[n + 1][m + 1];
        double[][] e = new double[n + 1][m + 1];
        double[][] f = new double[n + 1][m + 1];
        double neg = -1e9;
        for (int i = 0; i <= n; i++) {
            e[i][0] = neg;
            f[i][0] = neg;
        }
        for (int j = 0; j <= m; j++) {
            e[0][j] = neg;
            f[0][j] = neg;
        }
        double best = 0;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                e[i][j] = Math.max(h[i][j - 1] + GAP_OPEN, e[i][j - 1] + GAP_EXTEND);
                f[i][j] = Math.max(h[i - 1][j] + GAP_OPEN, f[i - 1][j] + GAP_EXTEND);
                h[i][j] = Math.max(0, Math.max(h[i - 1][j - 1] + sub(a.charAt(i - 1), b.charAt(j - 1)),
                        Math.max(e[i][j], f[i][j])));
                best = Math.max(best, h[i][j]);
            }
        }
        return best;
    }

    /** Minimal global edit cost with substitution 1 and gap 2. */
    static double needlemanWunschCost(String a, String b) {
        double[][] d = new double[a.length() + 1][b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            d[i][0] = i * NW_GAP_COST;
        }
        for (int j = 1; j <= b.length(); j++) {
            d[0][j] = j * NW_GAP_COST;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                double s = a.charAt(i - 1) == b.charAt(j - 1) ? 0.0 : NW_SUBSTITUTION_COST;
                d[i][j] = Math.min(d[i - 1][j - 1] + s, Math.min(d[i - 1][j] + NW_GAP_COST, d[i][j - 1] + NW_GAP_COST));
            }
        }
        return d[a.length()][b.length()];
    }

    @Override
    public float compare(String s1, String s2) {
        String a = s1.toLowerCase(Locale.ROOT);
        String b = s2.toLowerCase(Locale.ROOT);
        if (a.isEmpty() || b.isEmpty()) {
            return a.isEmpty() && b.isEmpty() ? 1f : 0f;
        }
        double v = switch (algorithm) {
            case SMITH_WATERMAN -> smithWaterman(a, b) / (MATCH * Math.min(a.length(), b.length()));
            case SMITH_WATERMAN_GOTOH -> smithWatermanGotoh(a, b) / (MATCH * Math.min(a.length(), b.length()));
            default -> 1.0 - needlemanWunschCost(a, b)
                    / (Math.max(a.length(), b.length()) * Math.max(NW_GAP_COST, NW_SUBSTITUTION_COST));
        };
        return (float) Math.max(0.0, Math.min(1.0, v));
    }
}
