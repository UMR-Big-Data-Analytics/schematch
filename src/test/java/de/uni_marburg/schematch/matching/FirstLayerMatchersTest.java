package de.uni_marburg.schematch.matching;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Database;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matching.control.ConstantMatcher;
import de.uni_marburg.schematch.matching.control.OracleMatcher;
import de.uni_marburg.schematch.matching.external.PythonServerMatcher;
import de.uni_marburg.schematch.matching.metadata.UnaryStatisticsMatcher;
import de.uni_marburg.schematch.matching.similarity.instance.ContainmentMatcher;
import de.uni_marburg.schematch.matching.similarity.instance.DistributionTestMatcher;
import de.uni_marburg.schematch.matching.similarity.instance.FuzzyJaccardMatcher;
import de.uni_marburg.schematch.matching.similarity.instance.MinHashMatcher;
import de.uni_marburg.schematch.matching.similarity.instance.PatternProfileMatcher;
import de.uni_marburg.schematch.matching.similarity.label.MongeElkanMatcher;
import de.uni_marburg.schematch.matching.similarity.label.PhoneticMatcher;
import de.uni_marburg.schematch.matching.similarity.label.PrefixSuffixMatcher;
import de.uni_marburg.schematch.matching.similarity.label.QGramMatcher;
import de.uni_marburg.schematch.matching.similarity.label.SmoaMatcher;
import de.uni_marburg.schematch.matching.similarity.label.SoftTfIdfMatcher;
import de.uni_marburg.schematch.matching.similarity.label.WordNetMatcher;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import de.uni_marburg.schematch.preprocessing.LabelNormalizer;
import de.uni_marburg.schematch.similarity.lexical.WordNet;
import de.uni_marburg.schematch.similarity.string.PrefixSuffix;
import de.uni_marburg.schematch.similarity.string.Smoa;
import de.uni_marburg.schematch.utils.Configuration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first-layer matchers added for the SF seed study. Reference values marked "Valentine",
 * "NLTK" or "SciPy" were computed with those implementations (sf-matcher-study/MATCHERS.md
 * records the script), so the ports are pinned to the originals, not to themselves.
 */
class FirstLayerMatchersTest {
    private static final float EPS = 1e-6f;

    static Table table(String name, Map<String, List<String>> columns) {
        List<String> labels = new ArrayList<>(columns.keySet());
        List<Column> cols = new ArrayList<>();
        for (String l : labels) {
            cols.add(new Column(l, columns.get(l)));
        }
        return new Table(name, labels, cols, name + ".csv", null);
    }

    static TablePair labelsOnly(List<String> source, List<String> target) {
        Map<String, List<String>> s = new java.util.LinkedHashMap<>();
        source.forEach(l -> s.put(l, List.of("x")));
        Map<String, List<String>> t = new java.util.LinkedHashMap<>();
        target.forEach(l -> t.put(l, List.of("x")));
        return new TablePair(table("s", s), table("t", t));
    }

    // ---------------------------------------------------------------- labels

    @Test
    void prefixSuffixMatchesValentine() {
        PrefixSuffix ps = new PrefixSuffix();
        // Valentine prefix_suffix_tokenized (code/valentine checkout)
        assertEquals(0.19753086419753085, ps.compare("date", "Birthdate"), EPS);
        assertEquals(0.06, ps.compare("int", "Department"), EPS);
        assertEquals(0.515625, ps.compare("EmployeeName", "empName"), EPS);
        assertEquals(0.75, ps.compare("dept_name", "DeptName"), EPS);
        assertEquals(0.4166666666666667, ps.compare("XMLParser", "xml_parser"), EPS);
    }

    @Test
    void labelNormalizer() {
        assertEquals(List.of("xml", "parser"), LabelNormalizer.tokens("XMLParser", "SPLIT"));
        assertEquals(List.of("order", "123"), LabelNormalizer.tokens("order123", "SPLIT"));
        assertEquals(List.of("department", "name"), LabelNormalizer.tokens("dept_name", "EXPAND"));
        assertEquals(List.of("category", "identifier"), LabelNormalizer.tokens("categories_id", "STEM"));
        assertEquals("city", LabelNormalizer.sStem("cities"));
        assertEquals("boxe", LabelNormalizer.sStem("boxes"));       // Harman: es -> e
        assertEquals("status", LabelNormalizer.sStem("status"));
        assertThrows(IllegalArgumentException.class, () -> LabelNormalizer.tokens("a", "BOGUS"));
    }

    @Test
    void smoaIdentityAndRange() {
        Smoa smoa = new Smoa();
        assertEquals(1.0f, smoa.compare("employee_name", "EmployeeName"), EPS);
        float v = smoa.compare("salary", "income");
        assertTrue(v >= 0 && v <= 1);
        assertTrue(smoa.compare("customer_id", "cust_id") > smoa.compare("customer_id", "price"));
    }

    @Test
    void tokenLabelMatchersRankTheRightPair() {
        TablePair tp = labelsOnly(List.of("EmployeeName", "dept_id", "Salary"),
                List.of("salary", "emp_name", "department_identifier"));
        Matcher[] matchers = {new MongeElkanMatcher(), new SoftTfIdfMatcher(), new QGramMatcher(),
                new SmoaMatcher(), new PhoneticMatcher(), new PrefixSuffixMatcher()};
        for (Matcher m : matchers) {
            float[][] s = ((TablePairMatcher) m).match(tp);
            assertEquals(3, s.length, m.toString());
            for (float[] row : s) {
                for (float v : row) {
                    assertTrue(v >= -EPS && v <= 1 + EPS, m + " out of range: " + v);
                }
            }
            // "Salary" is best matched by "salary" under every label measure
            assertTrue(s[2][0] >= s[2][1] && s[2][0] >= s[2][2], m.toString());
        }
    }

    // ---------------------------------------------------------------- WordNet

    @Test
    void wordNetMatchesNltk() {
        Assumptions.assumeTrue(new File(WordNet.defaultPath()).isFile(), "NLTK WordNet not installed");
        WordNet wn = WordNet.load("");
        // NLTK 3.6, max over noun sense pairs
        assertEquals(1.0, wn.wordSimilarity("car", "automobile", WordNet.WUP), 1e-9);
        assertEquals(0.8571428571428571, wn.wordSimilarity("dog", "cat", WordNet.WUP), 1e-9);
        assertEquals(0.2, wn.wordSimilarity("dog", "cat", WordNet.PATH), 1e-9);
        assertEquals(0.5575533219658061, wn.wordSimilarity("dog", "cat", WordNet.LCH), 1e-9);
        assertEquals(0.875, wn.wordSimilarity("city", "country", WordNet.WUP), 1e-9);
        assertEquals(0.3333333333333333, wn.wordSimilarity("city", "country", WordNet.PATH), 1e-9);
        assertEquals(0.5714285714285714, wn.wordSimilarity("name", "address", WordNet.WUP), 1e-9);
        assertEquals(0.14285714285714285, wn.wordSimilarity("name", "address", WordNet.PATH), 1e-9);
        assertEquals(1.0, wn.wordSimilarity("salary", "wage", WordNet.WUP), 1e-9);
        assertEquals(1.0, wn.wordSimilarity("customers", "client", WordNet.WUP), 1e-9); // morphy
        assertEquals(-1.0, wn.wordSimilarity("qwzx", "cat", WordNet.WUP), 1e-9);
        for (String m : List.of(WordNet.RES, WordNet.LIN, WordNet.JCN)) {
            double same = wn.wordSimilarity("dog", "dog", m);
            double near = wn.wordSimilarity("dog", "cat", m);
            double far = wn.wordSimilarity("dog", "invoice", m);
            assertTrue(near > far && same >= near, m + ": " + same + " " + near + " " + far);
        }
        assertEquals(List.of("zip_code", "x"), wn.compounds(List.of("zip", "code", "x"), 3));
    }

    @Test
    void wordNetMatcherFindsSynonyms() {
        Assumptions.assumeTrue(new File(WordNet.defaultPath()).isFile(), "NLTK WordNet not installed");
        TablePair tp = labelsOnly(List.of("salary", "customer_name"), List.of("client_name", "wage"));
        float[][] s = new WordNetMatcher().match(tp);
        assertEquals(1.0f, s[0][1], EPS);
        assertEquals(1.0f, s[1][0], EPS);
        assertTrue(s[0][0] < 1.0f);
    }

    // ---------------------------------------------------------------- instances

    static TablePair instances() {
        Map<String, List<String>> s = new java.util.LinkedHashMap<>();
        s.put("a", List.of("1", "2", "2", "3", "5", "8", "13"));
        s.put("b", List.of("red", "green", "blue", "", "red"));
        Map<String, List<String>> t = new java.util.LinkedHashMap<>();
        t.put("x", List.of("2", "3", "3", "4", "6", "9", "10", "11"));
        t.put("y", List.of("red", "blue", "black", "reed"));
        return new TablePair(table("s", s), table("t", t));
    }

    @Test
    void distributionTestsMatchScipy() {
        DistributionTestMatcher m = new DistributionTestMatcher();
        m.setDomain("NUMERIC");
        m.setTest("KS");
        assertEquals(1 - 0.30357142857142855, m.match(instances())[0][0], EPS);   // SciPy ks_2samp
        m.setTest("WASSERSTEIN");
        assertEquals(1 - 0.14583333333333334, m.match(instances())[0][0], EPS);   // SciPy / range
        m.setTest("JSD");
        m.setDomain("VALUE");
        float[][] s = m.match(instances());
        assertTrue(s[1][1] > s[1][0]);
        m.setTest("KS");
        assertThrows(IllegalArgumentException.class, () -> m.match(instances()));
    }

    @Test
    void jensenShannonMatchesScipy() {
        double js = de.uni_marburg.schematch.matching.similarity.instance.ColumnValues.jensenShannon(
                Map.of("a", 0.5, "b", 0.5), Map.of("a", 0.25, "c", 0.75));
        assertEquals(0.6556390622295666, js, 1e-12);                               // SciPy, base 2
    }

    @Test
    void overlapMatchers() {
        TablePair tp = instances();
        ContainmentMatcher c = new ContainmentMatcher();
        // {red, green, blue} vs {red, blue, black, reed}: |∩| = 2, containment max(2/3, 2/4)
        assertEquals(2.0 / 3.0, c.match(tp)[1][1], EPS);
        c.setBeta(1.0f);
        assertEquals(2.0 / 5.0, c.match(tp)[1][1], EPS);                         // Jaccard

        FuzzyJaccardMatcher f = new FuzzyJaccardMatcher();
        f.setDistanceFunction("EXACT");
        assertEquals(2.0 / 5.0, f.match(tp)[1][1], EPS);
        f.setDistanceFunction("LEVENSHTEIN");
        f.setThreshold(0.7f);
        // Levenshtein(red, reed) = 0.75 >= 0.7. A (smaller, 3 values): red, blue matched -> 2;
        // B: red, blue, reed matched -> 3. T(A,B) = 2/(2+1+1) = 0.5, T(B,A) = 3/(3+1+1) = 0.6
        assertEquals(0.6, f.match(tp)[1][1], EPS);

        MinHashMatcher mh = new MinHashMatcher();
        mh.setNumHashes(512);
        float[][] s = mh.match(tp);
        assertEquals(0.4, s[1][1], 0.1);
        assertEquals(0.0, s[1][0], 0.05);
    }

    @Test
    void patternAndStatistics() {
        Map<String, List<String>> s = new java.util.LinkedHashMap<>();
        s.put("date", List.of("2024-01-31", "1999-12-01"));
        s.put("code", List.of("AB12", "CD34"));
        Map<String, List<String>> t = new java.util.LinkedHashMap<>();
        t.put("d", List.of("2001-02-03"));
        t.put("c", List.of("XY99", "ZZ00", "QQ11"));
        TablePair tp = new TablePair(table("s", s), table("t", t));
        float[][] p = new PatternProfileMatcher().match(tp);
        assertEquals(1.0f, p[0][0], EPS);
        assertEquals(1.0f, p[1][1], EPS);
        assertEquals(0.0f, p[0][1], EPS);
        float[][] u = new UnaryStatisticsMatcher().match(tp);
        assertTrue(u[1][1] > u[1][0]);
    }

    // ---------------------------------------------------------------- controls

    @Test
    void oracleAndConstant(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("source"));
        Files.createDirectories(dir.resolve("target"));
        Files.createDirectories(dir.resolve("ground_truth"));
        Files.writeString(dir.resolve("source/s.csv"), "a,b\n1,2\n");
        Files.writeString(dir.resolve("target/t.csv"), "x,y,z\n1,2,3\n");
        Files.writeString(dir.resolve("ground_truth/s___t.csv"), "0,1,0\n1,0,0\n");
        Database src = new Database(dir.resolve("source").toString());
        Database tgt = new Database(dir.resolve("target").toString());
        TablePair tp = new TablePair(src.getTables().get(0), tgt.getTables().get(0));

        OracleMatcher o = new OracleMatcher();
        float[][] s = o.match(tp);
        assertArrayEquals(new float[]{0, 1, 0}, s[0], EPS);
        assertArrayEquals(new float[]{1, 0, 0}, s[1], EPS);
        o.setErase(1.0f);
        assertArrayEquals(new float[]{0, 0, 0}, o.match(tp)[0], EPS);
        o.setErase(0.0f);
        o.setNoise(0.5f);
        o.setSeed(7);
        float[][] n1 = o.match(tp);
        float[][] n2 = o.match(tp);
        assertArrayEquals(n1[0], n2[0], EPS);                                   // reproducible
        assertTrue(n1[0][1] >= 0.5f && n1[0][0] <= 0.5f);
        o.setNoise(0.0f);
        o.setDecoy(1.0f);
        float[][] d = o.match(tp);
        assertEquals(2.0f, d[0][0] + d[0][1] + d[0][2], EPS);                   // true + one decoy

        float[][] c = new ConstantMatcher().match(tp);
        assertArrayEquals(new float[]{1, 1, 1}, c[1], EPS);
    }

    // ---------------------------------------------------------------- plumbing

    @Test
    void everyMatcherConfiguresThroughTheFactory() throws Exception {
        MatcherFactory factory = new MatcherFactory();
        Object[][] cases = {
                {"PrefixSuffixMatcher", "similarity.label", Map.of("useIdf", true)},
                {"MongeElkanMatcher", "similarity.label", Map.of("inner", "LEVENSHTEIN", "normalization", "STEM")},
                {"SoftTfIdfMatcher", "similarity.label", Map.of("theta", 0.8)},
                {"QGramMatcher", "similarity.label", Map.of("q", 2, "coefficient", "DICE", "padding", false)},
                {"SmoaMatcher", "similarity.label", Map.of("minSubstring", 2)},
                {"PhoneticMatcher", "similarity.label", Map.of("algorithm", "SOUNDEX")},
                {"WordNetMatcher", "similarity.label", Map.of("measure", "LIN", "fallback", "JARO_WINKLER")},
                {"ContainmentMatcher", "similarity.instance", Map.of("alpha", 1.0, "beta", 1.0, "normalise", true)},
                {"FuzzyJaccardMatcher", "similarity.instance", Map.of("threshold", 0.9, "sampleSize", 100)},
                {"MinHashMatcher", "similarity.instance", Map.of("numHashes", 64, "estimate", "CONTAINMENT")},
                {"DistributionTestMatcher", "similarity.instance", Map.of("test", "WASSERSTEIN", "bins", 10)},
                {"PatternProfileMatcher", "similarity.instance", Map.of("granularity", "CHARCLASS")},
                {"UnaryStatisticsMatcher", "metadata", Map.of("recodingInvariant", true)},
                {"OracleMatcher", "control", Map.of("noise", 0.3, "erase", 0.1, "decoy", 0.0, "seed", 3)},
                {"ConstantMatcher", "control", Map.of("value", 0.5)},
                {"PythonServerMatcher", "external", Map.of("serverPort", 5101, "method", "sbert",
                        "options", "{\"model\": \"all-mpnet-base-v2\"}")},
        };
        for (Object[] c : cases) {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = new HashMap<>((Map<String, Object>) c[2]);
            Matcher m = factory.createMatcherInstance(
                    new Configuration.MatcherConfiguration((String) c[0], (String) c[1], params));
            assertEquals(c[0], m.getClass().getSimpleName());
        }
    }

    @Test
    void pythonServerResponseParsing() throws Exception {
        java.lang.reflect.Method parse = PythonServerMatcher.class.getDeclaredMethod("parse", String.class, float[][].class);
        parse.setAccessible(true);
        float[][] s = (float[][]) parse.invoke(null, "0.5 1\n0 0.25\n", new float[2][2]);
        assertArrayEquals(new float[]{0.5f, 1f}, s[0], EPS);
        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> parse.invoke(null, "0.5 1\n", new float[2][2]));
    }
}
