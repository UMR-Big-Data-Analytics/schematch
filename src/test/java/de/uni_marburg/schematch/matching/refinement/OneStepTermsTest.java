package de.uni_marburg.schematch.matching.refinement;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.uni_marburg.schematch.data.Database;
import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.utils.Configuration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OneStepTermsTest {
    @TempDir Path dir;

    private static void matrix(double[][] expected, double[][] actual, double tolerance, String what) {
        assertEquals(expected.length, actual.length, what);
        for (int i = 0; i < expected.length; i++) for (int j = 0; j < expected[i].length; j++)
            assertEquals(expected[i][j], actual[i][j], tolerance, what + " cell " + i + "," + j);
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static Scenario load(Path path, boolean dependencies) {
        Configuration config = Configuration.getInstance();
        boolean old = config.isReadDependencies();
        try { config.setReadDependencies(dependencies); return new Scenario(path.toString()); }
        finally { config.setReadDependencies(old); }
    }

    /** One table per side: integer, decimal and code columns, with FD/UCC/IND profiles. */
    private Scenario singleTable() throws Exception {
        write(dir.resolve("source/s.csv"), "id,price,code\n1,1.5,AB-12\n2,2.25,cd-34\n3,3.0,x\n");
        write(dir.resolve("target/t.csv"), "key,cost,label\n7,9.5,XY-99\n8,1.25,zz-11\n9,0.5,q1\n");
        write(dir.resolve("metadata/source/s/FD_results.txt"),
                "[s.csv.id] --> s.csv.price (pdep 1.0, 0.0)\n[s.csv.id] --> s.csv.code (pdep 1.0, 0.0)\n");
        write(dir.resolve("metadata/source/s/UCC_results.txt"), "[s.csv.id]\n[s.csv.price, s.csv.code]\n");
        write(dir.resolve("metadata/source/inds.txt"), "[s.csv.code] [= [s.csv.id]\n");
        write(dir.resolve("metadata/target/t/FD_results.txt"), "[t.csv.key] --> t.csv.cost\n");
        write(dir.resolve("metadata/target/t/UCC_results.txt"), "[t.csv.key]\n");
        write(dir.resolve("metadata/target/inds.txt"), "");
        return load(dir, true);
    }

    private static final float[][] Q = {{.9f, -.2f, .1f}, {.3f, .7f, 0f}, {-1f, .2f, .6f}};

    @Test
    void datatypeTermAddsFiveTimesTheNanFreeDatatypeMatrix() throws Exception {
        Scenario scenario = singleTable();
        double[][] d = AdditiveTermMatcher.datatypes(scenario);
        // Integer/integer and decimal/decimal 1, integer/decimal 0.8 (DatatypeMatcher); codes are strings.
        assertEquals(1, d[0][0], 1e-7); assertEquals(.8, d[0][1], 1e-7); assertEquals(.8, d[1][0], 1e-7);
        assertEquals(1, d[1][1], 1e-7); assertEquals(1, d[2][2], 1e-7);
        double[][] out = new DatatypeTermMatcher().combine(Q, scenario);
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
            assertEquals(Math.max(Q[i][j], 0) + 5 * d[i][j], out[i][j], 1e-12, "cell " + i + "," + j);
        assertEquals(-.2f, Q[0][1]); // The input is not modified.
    }

    @Test
    void valueShapeClassesFollowTheStudyRule() throws Exception {
        assertEquals("Aa Aa 9", ClassTermMatcher.shape("Hello  World 2024"));
        assertEquals("A-9", ClassTermMatcher.shape("AB-12"));
        assertEquals("a9", ClassTermMatcher.shape("x²"));        // superscript two is a digit in Python
        assertEquals(" ", ClassTermMatcher.shape(" \t"));         // no-break space is whitespace in Python
        assertEquals("9a".repeat(16), ClassTermMatcher.shape("1" + "x1".repeat(20)));  // at most 32 characters
        assertEquals("a", ClassTermMatcher.columnClass(List.of("x", "1", "", "")));   // tie: first shape; empty skipped
        assertEquals("MIXED", ClassTermMatcher.columnClass(List.of("x", "1", "A")));
        assertEquals("MIXED", ClassTermMatcher.columnClass(List.of("", " ", "  ")));  // whitespace is no class
        assertEquals("MIXED", ClassTermMatcher.columnClass(List.of("")));
        Scenario scenario = singleTable();
        assertArrayEquals(new String[]{"9", "9.9", "MIXED"}, ClassTermMatcher.classes(scenario.getSourceDatabase()));
        assertArrayEquals(new String[]{"9", "9.9", "MIXED"}, ClassTermMatcher.classes(scenario.getTargetDatabase()));
        ClassTermMatcher matcher = new ClassTermMatcher();
        matcher.setDatatypeWeight(0);
        double[][] out = matcher.combine(Q, scenario);
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)   // MIXED equals MIXED
            assertEquals(Math.max(Q[i][j], 0) + (i == j ? .2 : 0), out[i][j], 1e-7, "cell " + i + "," + j);
    }

    @Test
    void roleTermComparesNormalisedDependencyRoles() throws Exception {
        Scenario scenario = singleTable();
        // id: 2 FD LHS, in the arity-1 key, referenced by code; price/code: FD RHS and in the 2-column UCC.
        double[][] s = RoleTermMatcher.roles(scenario.getSourceDatabase());
        assertArrayEquals(new double[]{1, 0, 1, 1, 0, 1}, s[0]);
        assertArrayEquals(new double[]{0, 1, 1, 0, 0, 0}, s[1]);
        assertArrayEquals(new double[]{0, 1, 1, 0, 1, 0}, s[2]);
        RoleTermMatcher matcher = new RoleTermMatcher();
        matcher.setDatatypeWeight(0);
        matcher.setWeight(6);
        double[][] r = {{5, 1, 2}, {3, 5, 4}, {2, 4, 3}}; // 6 R, from |differences| summed by hand
        double[][] out = matcher.combine(new float[3][3], scenario);
        matrix(r, out, 1e-12, "6R");
        Database unprofiled = load(dir, false).getSourceDatabase();
        assertThrows(IllegalStateException.class, () -> RoleTermMatcher.roles(unprofiled));
    }

    @Test
    void tableContextMatchesThePythonReferenceOnSeveralTables() throws Exception {
        write(dir.resolve("source/A.csv"), "a1,a2\n1,2\n");
        write(dir.resolve("source/B.csv"), "b1\nx\n");
        write(dir.resolve("target/X.csv"), "x1\n1\n");
        write(dir.resolve("target/Y.csv"), "y1,y2\n1,y\n");
        Scenario scenario = load(dir, false);
        float[][] q = {{.8f, .2f, -.5f}, {.4f, .6f, 0f}, {.1f, 0f, 1f}};
        // T(A,X) = .7, T(A,Y) = .35, T(B,X) = .1, T(B,Y) = .75; C = T / .75. sf-matcher-study/reference/
        // score_tablectx.py table_context on the float32 values with labels A.a1, A.a2, B.b1 x X.x1, Y.y1, Y.y2,
        // 2026-10-30.
        double[][] python = {{0.9333333472410837, 0.46666668355464935, 0.46666668355464935},
                {0.9333333472410837, 0.46666668355464935, 0.46666668355464935},
                {0.13333333532015482, 1.0, 1.0}};
        double[][] c = TableContextTermMatcher.context(q, scenario.getSourceDatabase().getTables(),
                scenario.getTargetDatabase().getTables());
        matrix(python, c, 1e-12, "C");
        TableContextTermMatcher matcher = new TableContextTermMatcher();
        matcher.setDatatypeWeight(0);
        double[][] out = matcher.combine(q, scenario);
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
            assertEquals(Math.max(q[i][j], 0) + .5 * python[i][j], out[i][j], 1e-12);
        matrix(new double[3][3], TableContextTermMatcher.context(new float[3][3], scenario.getSourceDatabase().getTables(),
                scenario.getTargetDatabase().getTables()), 0, "zero input");
    }

    @Test
    void literalTermIsOneValuePerDatatypeBlockAndIndependentOfTheInput() throws Exception {
        write(dir.resolve("source/s.csv"), "a,b,c\n1,2,x\n3,4,y\n");
        write(dir.resolve("target/t.csv"), "u,v,w\n5,6,p\n7,8,q\n");
        Scenario scenario = load(dir, false);
        LiteralTermMatcher matcher = new LiteralTermMatcher();
        matcher.setDatatypeWeight(0);
        matcher.setForm("RAW");
        matcher.setWeight(1);
        double[][] l = matcher.combine(new float[3][3], scenario);
        double[][] again = matcher.combine(new float[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}}, scenario);
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            assertTrue(l[i][j] > 0);
            assertEquals(l[i][j] + (i == j ? 1 : 0), again[i][j], 1e-12);   // L does not depend on q
        }
        // One table pair (Proposition 1): one literal value per datatype block, here integer x integer.
        assertEquals(l[0][0], l[0][1], 1e-12);
        assertEquals(l[0][0], l[1][0], 1e-12);
        assertEquals(l[0][0], l[1][1], 1e-12);
        assertEquals(l[0][2], l[1][2], 1e-12);
        assertEquals(l[2][0], l[2][1], 1e-12);
        matcher.setForm("RESCALED");
        matcher.setWeight(.1);
        double top = 0;
        for (double[] row : matcher.combine(new float[3][3], scenario)) for (double v : row) top = Math.max(top, v);
        assertEquals(.1, top, 1e-12);
        matcher.setForm("TWO_UPDATE");
        double[][] two = matcher.combine(new float[3][3], scenario);
        double c = two[0][0] / l[0][0];
        assertTrue(c > 0 && c <= .5, "c = 2 / (M1 + 4)");
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) assertEquals(c * l[i][j], two[i][j], 1e-12);
        matcher.setForm("OTHER");
        assertThrows(IllegalArgumentException.class, () -> matcher.combine(new float[3][3], scenario));
    }

    @Test
    void weightsAreValidatedAndRefineReturnsFloats() throws Exception {
        Scenario scenario = singleTable();
        assertEquals("LiteralTermMatcher(datatypeWeight=5.0, weight=0.05, form=RESCALED, initialMatrix=RAW_FIRST_LINE, "
                + "baseMatcherIndex=0, base=)", new LiteralTermMatcher().toString());
        ClassTermMatcher matcher = new ClassTermMatcher();
        float[][] out = matcher.refine(Q, scenario);
        double[][] exact = matcher.combine(Q, scenario);
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) assertEquals((float) exact[i][j], out[i][j]);
        matcher.setWeight(Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> matcher.refine(Q, scenario));
        assertThrows(IllegalArgumentException.class, () -> new DatatypeTermMatcher().refine(new float[2][3], scenario));
        assertThrows(IllegalArgumentException.class, () -> new DatatypeTermMatcher().refine(new float[][]{{Float.NaN, 0, 0},
                {0, 0, 0}, {0, 0, 0}}, scenario));
    }

    // ---------------------------------------------------------------------------------------------------- Valentine
    /** Small Valentine scenarios with expected matrices from the study's Python reference functions. */
    private static final String[] VALENTINE = {"Magellan-Unionable__dblp_acm", "Magellan-Unionable__fodors_zagats",
            "ChEMBL-Semantically-Joinable__assays_both_50_1_ac1_av"};

    /**
     * Opt-in: -Dschematch.test.valentineRoot=&lt;directory with Valentine-layout datasets and FD/UCC/IND profiles&gt;
     * (dataset/scenario/{source,target,metadata}). Skipped without it; the scenarios are not bundled.
     */
    @Test
    @SuppressWarnings("unchecked")
    void termsMatchThePythonReferencesOnValentine() throws Exception {
        String root = System.getProperty("schematch.test.valentineRoot", "");
        Assumptions.assumeTrue(!root.isBlank(), "set schematch.test.valentineRoot to run the Valentine parity check");
        ObjectMapper json = new ObjectMapper();
        for (String name : VALENTINE) {
            Map<String, Object> rec;
            try (InputStream in = getClass().getResourceAsStream("one_step_terms/" + name + ".json")) {
                rec = json.readValue(in, Map.class);
            }
            Path path = Path.of(root, (String) rec.get("dataset"), (String) rec.get("scenario"));
            Assumptions.assumeTrue(Files.isDirectory(path), "missing " + path);
            Scenario scenario = load(path, true);
            List<String> rows = new ArrayList<>(), cols = new ArrayList<>();
            for (int i = 0; i < scenario.getSourceDatabase().getNumColumns(); i++)
                rows.add(scenario.getSourceDatabase().getFullColumnNameByIndex(i));
            for (int j = 0; j < scenario.getTargetDatabase().getNumColumns(); j++)
                cols.add(scenario.getTargetDatabase().getFullColumnNameByIndex(j));
            assertEquals(rec.get("rows"), rows, name);
            assertEquals(rec.get("cols"), cols, name);
            float[][] q = floats((List<List<Number>>) rec.get("q"));
            Map<String, List<List<Number>>> expected = (Map<String, List<List<Number>>>) rec.get("expected");
            LiteralTermMatcher raw = new LiteralTermMatcher(), two = new LiteralTermMatcher();
            raw.setForm("RAW"); raw.setWeight(.3); raw.setDatatypeWeight(0);
            two.setForm("TWO_UPDATE"); two.setDatatypeWeight(0);
            Map<String, AdditiveTermMatcher> matchers = Map.of("DatatypeTermMatcher", new DatatypeTermMatcher(),
                    "ClassTermMatcher", new ClassTermMatcher(), "RoleTermMatcher", new RoleTermMatcher(),
                    "TableContextTermMatcher", new TableContextTermMatcher(), "LiteralTermMatcher", new LiteralTermMatcher(),
                    "LiteralTermMatcher/RAW", raw, "LiteralTermMatcher/TWO_UPDATE", two);
            assertEquals(matchers.keySet(), expected.keySet());
            for (Map.Entry<String, AdditiveTermMatcher> m : matchers.entrySet()) {
                double[][] want = doubles(expected.get(m.getKey()));
                double[][] got = m.getValue().combine(q, scenario);
                matrix(want, got, 1e-9, name + " " + m.getKey());
                if (System.getProperty("schematch.test.verbose") != null) {
                    double worst = 0;
                    for (int i = 0; i < want.length; i++) for (int j = 0; j < want[i].length; j++)
                        worst = Math.max(worst, Math.abs(want[i][j] - got[i][j]));
                    System.out.println("PARITY\t" + name + "\t" + m.getKey() + "\t" + worst);
                }
            }
        }
    }

    private static float[][] floats(List<List<Number>> rows) {
        float[][] out = new float[rows.size()][];
        for (int i = 0; i < out.length; i++) {
            out[i] = new float[rows.get(i).size()];
            for (int j = 0; j < out[i].length; j++) out[i][j] = rows.get(i).get(j).floatValue();
        }
        return out;
    }

    private static double[][] doubles(List<List<Number>> rows) {
        double[][] out = new double[rows.size()][];
        for (int i = 0; i < out.length; i++) {
            out[i] = new double[rows.get(i).size()];
            for (int j = 0; j < out[i].length; j++) out[i][j] = rows.get(i).get(j).doubleValue();
        }
        return out;
    }
}
