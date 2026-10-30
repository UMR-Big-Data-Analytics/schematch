package de.uni_marburg.schematch.matching.similarityFlooding;

import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.data.metadata.Datatype;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proposition 1 (datatype-block invariant) of the SF study: on one table per side and without dependency edges,
 * every finite iterate of formula C is sigma^t(p_ij) = a_t q_ij + b_t(dt(i), dt(j)) with a_t > 0, so SF keeps the
 * order and the ties of the initial scores within each datatype-pair block. Checked on synthetic schemas for both
 * coefficient policies, NAME, ID and SPLIT attachment, with and without literal seeds, after 1, 2, 3 and 5 updates
 * and converged (sf-matcher-study reference/check_prop1.py, layer3b/check_sf_export.py). The Valentine version is
 * SfParityTest.propositionOneHoldsOnValentine.
 */
class PropositionOneTest {
    @TempDir Path dir;

    /** Within-block pairs with q higher by more than dq and SF lower by more than ds. */
    static int inversions(float[][] q, float[][] s, Datatype[] rows, Datatype[] cols, double dq, double ds) {
        List<int[]> cells = new ArrayList<>();
        for (int i = 0; i < q.length; i++) for (int j = 0; j < q[i].length; j++) cells.add(new int[]{i, j});
        int count = 0;
        for (int[] a : cells) for (int[] b : cells) {
            if (rows[a[0]] != rows[b[0]] || cols[a[1]] != cols[b[1]]) continue;
            if (q[a[0]][a[1]] - q[b[0]][b[1]] > dq && s[a[0]][a[1]] - s[b[0]][b[1]] < -ds) count++;
        }
        return count;
    }

    private Scenario scenario() throws Exception {
        // Integers, decimals, text and dates on both sides, several columns per datatype.
        Files.createDirectories(dir.resolve("data/case/source"));
        Files.createDirectories(dir.resolve("data/case/target"));
        Files.writeString(dir.resolve("data/case/source/s.csv"), "id,qty,price,rate,name,city,born\n"
                + "1,4,1.5,0.25,Anna Berg,Berlin,1990-01-02\n2,7,2.75,0.5,Bo Lund,Oslo,1985-03-04\n"
                + "3,9,3.0,0.75,Cy Moor,Rome,1979-05-06\n");
        Files.writeString(dir.resolve("data/case/target/t.csv"), "key,count,cost,fee,label,town,since,code\n"
                + "7,1,9.5,1.25,Dee Nash,Paris,2001-07-08,x1\n8,2,1.25,2.5,Ed Ott,Madrid,1999-09-10,y2\n"
                + "9,3,0.5,3.75,Flo Pike,Vienna,2010-11-12,z3\n");
        return SfTestSupport.load(dir.resolve("data/case"), false);
    }

    private String seed(Scenario scenario, float[][] q) throws Exception {
        StringBuilder csv = new StringBuilder("Source\\Target");
        for (int j = 0; j < q[0].length; j++) csv.append(',').append(scenario.getTargetDatabase().getFullColumnNameByIndex(j));
        csv.append('\n');
        for (int i = 0; i < q.length; i++) {
            csv.append(scenario.getSourceDatabase().getFullColumnNameByIndex(i));
            for (float v : q[i]) csv.append(',').append(v);
            csv.append('\n');
        }
        return SfTestSupport.writeSeed(dir.resolve("seeds"), "data", "case", csv.toString()).toString();
    }

    @Test
    void finiteIteratesAreAffineInTheInitialScoresWithinDatatypeBlocks() throws Exception {
        Scenario scenario = scenario();
        Datatype[] rows = SfParityTest.datatypes(scenario.getSourceDatabase());
        Datatype[] cols = SfParityTest.datatypes(scenario.getTargetDatabase());
        assertTrue(java.util.Arrays.stream(rows).distinct().count() >= 3, "several datatype blocks");
        Random random = new Random(20261030L);
        float[][] q = new float[rows.length][cols.length];
        for (float[] row : q) for (int j = 0; j < row.length; j++) row[j] = random.nextInt(8) / 7f;  // with ties
        q[0][0] = q[1][0];
        String seeds = seed(scenario, q);
        int checked = 0;
        for (String policy : List.of("INV_AVG", "INV_PROD")) {
            for (String attach : List.of("NAME", "ID", "SPLIT")) {
                for (String literal : List.of("NONE", "PREFIX_SUFFIX")) {
                    for (String cap : List.of("0", "1", "2", "4", "200")) {
                        SimilarityFlooding sf = new SimilarityFloodingPublished();
                        sf.setInitialMatrix("FILE");
                        sf.setInitialMatrixDir(seeds);
                        sf.setPropCoeffPolicy(policy);
                        sf.setSeedAttachment(attach);
                        sf.setLiteralSeed(literal);
                        sf.setMaxIterations(cap);
                        sf.setEpsilon("1e-9");
                        float[][] s = SfTestSupport.run(sf, scenario);
                        String what = policy + " " + attach + " " + literal + " maxIterations=" + cap;
                        assertEquals(0, inversions(q, s, rows, cols, 1e-9, 1e-6), what);
                        assertAffine(q, s, rows, cols, what);
                        checked++;
                    }
                }
            }
        }
        assertEquals(60, checked);
    }

    /** One common slope a > 0 across all blocks, ties kept: s_ij - s_kl = a (q_ij - q_kl) within each block. */
    private static void assertAffine(float[][] q, float[][] s, Datatype[] rows, Datatype[] cols, String what) {
        double slope = Double.NaN, max = 0;
        for (float[] row : s) for (float v : row) max = Math.max(max, Math.abs(v));
        for (int a = 0; a < q.length * q[0].length; a++) {
            for (int b = 0; b < q.length * q[0].length; b++) {
                int i = a / q[0].length, j = a % q[0].length, k = b / q[0].length, l = b % q[0].length;
                if (rows[i] != rows[k] || cols[j] != cols[l]) continue;
                double dq = q[i][j] - q[k][l], ds = (double) s[i][j] - s[k][l];
                if (dq == 0) {
                    assertEquals(0.0, ds, 1e-6 * max, what + ": tie not kept");
                } else if (Double.isNaN(slope)) {
                    slope = ds / dq;
                    assertTrue(slope > 0, what + ": slope " + slope);
                } else {
                    assertEquals(slope * dq, ds, 1e-5 * max, what + ": not affine with a common slope");
                }
            }
        }
        assertFalse(Double.isNaN(slope), what + ": no block with two different scores");
    }
}
