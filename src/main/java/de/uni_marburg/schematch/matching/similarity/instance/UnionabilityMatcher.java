package de.uni_marburg.schematch.matching.similarity.instance;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matching.TablePairMatcher;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Set-unionability of Table Union Search (Nargesian, Zhu, Pu &amp; Miller, PVLDB 2018): the
 * probability that two columns drawn at random from a common domain D would overlap <em>less</em>
 * than they do, {@code U(A,B) = P(X <= |A ∩ B|)} with
 * {@code X ~ Hypergeometric(N = |D|, K = |A|, n = |B|)}.
 *
 * <p>Unlike Jaccard it is calibrated by the domain: a small overlap between two small columns from
 * a huge domain is strong evidence. {@code domain}: TABLES (default; D is the set of all distinct
 * values in both tables, the closest a single table pair gets to TUS's data-lake domain) or PAIR
 * (D = A ∪ B, which makes the score depend on the overlap fraction only). TUS's semantic and
 * natural-language unionability need an ontology and word embeddings; the NL variant is the
 * {@code sbert} seed with label_values.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UnionabilityMatcher extends TablePairMatcher {
    private String domain = "TABLES";
    private Boolean normalise = false;

    @Override
    public float[][] match(TablePair tablePair) {
        Table s = tablePair.getSourceTable();
        Table t = tablePair.getTargetTable();
        boolean norm = Boolean.TRUE.equals(normalise);
        List<Set<String>> left = new ArrayList<>();
        Set<String> all = new HashSet<>();
        for (Column c : s.getColumns()) {
            Set<String> v = ColumnValues.distinct(c, norm);
            left.add(v);
            all.addAll(v);
        }
        List<Set<String>> right = new ArrayList<>();
        for (Column c : t.getColumns()) {
            Set<String> v = ColumnValues.distinct(c, norm);
            right.add(v);
            all.addAll(v);
        }
        String d = domain.toUpperCase(Locale.ROOT);
        if (!d.equals("TABLES") && !d.equals("PAIR")) {
            throw new IllegalArgumentException("Unknown unionability domain: " + domain);
        }
        float[][] sim = tablePair.getEmptySimMatrix();
        for (int i = 0; i < left.size(); i++) {
            for (int j = 0; j < right.size(); j++) {
                Set<String> a = left.get(i);
                Set<String> b = right.get(j);
                if (a.isEmpty() || b.isEmpty()) {
                    continue;
                }
                int inter = 0;
                for (String v : a.size() <= b.size() ? a : b) {
                    if ((a.size() <= b.size() ? b : a).contains(v)) {
                        inter++;
                    }
                }
                if (inter == 0) {
                    // P(X <= 0) is near 1 whenever chance overlap is tiny, which would call two
                    // disjoint columns unionable; TUS only scores LSH candidates that do overlap.
                    continue;
                }
                int n = d.equals("PAIR") ? a.size() + b.size() - inter : all.size();
                sim[i][j] = (float) hypergeometricCdf(inter, n, a.size(), b.size());
            }
        }
        return sim;
    }

    /** P(X <= x) for X ~ Hypergeometric(population N, K successes, n draws), computed in log space. */
    static double hypergeometricCdf(int x, int N, int K, int n) {
        int lo = Math.max(0, n + K - N);
        int hi = Math.min(K, n);
        if (x < lo) {
            return 0.0;
        }
        if (x >= hi) {
            return 1.0;
        }
        double denom = logChoose(N, n);
        double cdf = 0.0;
        for (int k = lo; k <= x; k++) {
            cdf += Math.exp(logChoose(K, k) + logChoose(N - K, n - k) - denom);
        }
        return Math.max(0.0, Math.min(1.0, cdf));
    }

    static double logChoose(int n, int k) {
        if (k < 0 || k > n) {
            return Double.NEGATIVE_INFINITY;
        }
        return logGamma(n + 1.0) - logGamma(k + 1.0) - logGamma(n - k + 1.0);
    }

    /** Lanczos approximation (g = 7, 9 terms), accurate to ~1e-15 for x > 0. */
    static double logGamma(double x) {
        double[] c = {0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
                -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6,
                1.5056327351493116e-7};
        if (x < 0.5) {
            return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - logGamma(1 - x);
        }
        x -= 1;
        double a = c[0];
        double t = x + 7.5;
        for (int i = 1; i < 9; i++) {
            a += c[i] / (x + i);
        }
        return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(a);
    }
}
