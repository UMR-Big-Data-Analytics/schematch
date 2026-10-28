package de.uni_marburg.schematch.matching.ensemble;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Maximum-weight 1:1 assignments of a score matrix: the best one (Hungarian / Kuhn-Munkres,
 * O(N^3) on the matrix padded to square with zeros) and the K best (Murty 1968, partitioning the
 * solution space by forcing and excluding pairs). Used by {@link ProbabilisticMappingMatcher}.
 *
 * <p>An assignment is returned as {@code int[rows]} with the matched column or -1. Padding pairs
 * are dropped, so assignments that differ only in how dummy rows/columns are paired are the same
 * real assignment and are reported once.
 */
public final class Assignments {
    private static final double FORBIDDEN = -1e12;

    private Assignments() {
    }

    public record Solution(int[] assignment, double score) {
    }

    /** Best assignment of a rows x cols score matrix (maximisation). */
    public static Solution best(double[][] score) {
        int n = score.length;
        int m = n == 0 ? 0 : score[0].length;
        int size = Math.max(n, m);
        double[][] cost = new double[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                double s = (i < n && j < m) ? score[i][j] : 0.0;
                cost[i][j] = -s;
            }
        }
        int[] rowToCol = hungarianMin(cost);
        int[] a = new int[n];
        double total = 0;
        for (int i = 0; i < n; i++) {
            int j = rowToCol[i];
            a[i] = j < m ? j : -1;
            if (a[i] >= 0) {
                total += score[i][j];
            }
        }
        return new Solution(a, total);
    }

    /** Classic O(N^3) Hungarian algorithm (potentials + augmenting paths) for a square cost matrix. */
    static int[] hungarianMin(double[][] a) {
        int n = a.length;
        double[] u = new double[n + 1];
        double[] v = new double[n + 1];
        int[] p = new int[n + 1];
        int[] way = new int[n + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            double[] minv = new double[n + 1];
            Arrays.fill(minv, Double.POSITIVE_INFINITY);
            boolean[] used = new boolean[n + 1];
            do {
                used[j0] = true;
                int i0 = p[j0];
                double delta = Double.POSITIVE_INFINITY;
                int j1 = 0;
                for (int j = 1; j <= n; j++) {
                    if (!used[j]) {
                        double cur = a[i0 - 1][j - 1] - u[i0] - v[j];
                        if (cur < minv[j]) {
                            minv[j] = cur;
                            way[j] = j0;
                        }
                        if (minv[j] < delta) {
                            delta = minv[j];
                            j1 = j;
                        }
                    }
                }
                for (int j = 0; j <= n; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }
        int[] rowToCol = new int[n];
        for (int j = 1; j <= n; j++) {
            if (p[j] > 0) {
                rowToCol[p[j] - 1] = j - 1;
            }
        }
        return rowToCol;
    }

    private record Node(Solution solution, Set<Long> forced, Set<Long> excluded) {
    }

    private static long key(int i, int j) {
        return ((long) i << 32) | (j & 0xffffffffL);
    }

    /** Solve under constraints; null when infeasible (a forced or the only remaining pair is excluded). */
    private static Solution constrained(double[][] score, Set<Long> forced, Set<Long> excluded) {
        int n = score.length;
        int m = n == 0 ? 0 : score[0].length;
        double[][] s = new double[n][m];
        for (int i = 0; i < n; i++) {
            s[i] = score[i].clone();
        }
        for (long f : forced) {
            int fi = (int) (f >> 32);
            int fj = (int) f;
            for (int j = 0; j < m; j++) {
                if (j != fj) {
                    s[fi][j] = FORBIDDEN;
                }
            }
            for (int i = 0; i < n; i++) {
                if (i != fi) {
                    s[i][fj] = FORBIDDEN;
                }
            }
        }
        for (long e : excluded) {
            s[(int) (e >> 32)][(int) e] = FORBIDDEN;
        }
        Solution sol = best(s);
        for (int i = 0; i < n; i++) {
            int j = sol.assignment()[i];
            if (j >= 0 && s[i][j] <= FORBIDDEN / 2) {
                // the padded square always has a solution through dummy pairs, so taking a
                // forbidden real pair means no assignment satisfies the constraints
                return null;
            }
        }
        for (long f : forced) {
            if (sol.assignment()[(int) (f >> 32)] != (int) f) {
                return null;
            }
        }
        double total = 0;
        for (int i = 0; i < n; i++) {
            int j = sol.assignment()[i];
            if (j >= 0) {
                total += score[i][j];
            }
        }
        return new Solution(sol.assignment(), total);
    }

    /** The K best distinct real assignments, best first (Murty's ranking). */
    public static List<Solution> kBest(double[][] score, int k) {
        List<Solution> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        PriorityQueue<Node> queue = new PriorityQueue<>((x, y) -> Double.compare(y.solution().score(), x.solution().score()));
        Solution first = constrained(score, new HashSet<>(), new HashSet<>());
        if (first == null) {
            return out;
        }
        queue.add(new Node(first, new HashSet<>(), new HashSet<>()));
        while (!queue.isEmpty() && out.size() < k) {
            Node node = queue.poll();
            int[] a = node.solution().assignment();
            String sig = Arrays.toString(a);
            if (seen.add(sig)) {
                out.add(node.solution());
            }
            // partition on the real pairs of this solution that are not already forced
            Set<Long> forced = new HashSet<>(node.forced());
            for (int i = 0; i < a.length; i++) {
                if (a[i] < 0 || forced.contains(key(i, a[i]))) {
                    continue;
                }
                Set<Long> excluded = new HashSet<>(node.excluded());
                excluded.add(key(i, a[i]));
                Solution child = constrained(score, forced, excluded);
                if (child != null) {
                    queue.add(new Node(child, new HashSet<>(forced), excluded));
                }
                forced.add(key(i, a[i]));
            }
        }
        return out;
    }
}
