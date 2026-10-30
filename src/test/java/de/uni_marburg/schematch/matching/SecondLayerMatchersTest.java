package de.uni_marburg.schematch.matching;

import de.uni_marburg.schematch.matching.ensemble.Assignments;
import de.uni_marburg.schematch.matching.metadata.SemIntMatcher;
import de.uni_marburg.schematch.matching.similarity.instance.CharDistributionMatcher;
import de.uni_marburg.schematch.matching.similarity.instance.DistributionShapeMatcher;
import de.uni_marburg.schematch.matching.similarity.instance.UnionabilityMatcher;
import de.uni_marburg.schematch.matching.similarity.label.NormsMatcher;
import de.uni_marburg.schematch.matching.similarity.label.ThesaurusMatcher;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import de.uni_marburg.schematch.preprocessing.PorterStemmer;
import de.uni_marburg.schematch.similarity.string.Alignment;
import de.uni_marburg.schematch.utils.Configuration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static de.uni_marburg.schematch.matching.FirstLayerMatchersTest.labelsOnly;
import static de.uni_marburg.schematch.matching.FirstLayerMatchersTest.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The second-layer matchers; references named where an original implementation exists. */
class SecondLayerMatchersTest {
    private static final float EPS = 1e-6f;

    // NLTK 3.6 PorterStemmer(mode=MARTIN_EXTENSIONS), Porter's own reference version
    private static final String PORTER_REFERENCE = "caresses=caress;ponies=poni;ties=ti;caress=caress;cats=cat;"
            + "feed=feed;agreed=agre;plastered=plaster;motoring=motor;sing=sing;conflated=conflat;troubled=troubl;"
            + "sized=size;hopping=hop;tanned=tan;falling=fall;hissing=hiss;fizzed=fizz;failing=fail;filing=file;"
            + "happy=happi;sky=sky;relational=relat;conditional=condit;rational=ration;valenci=valenc;"
            + "hesitanci=hesit;digitizer=digit;conformabli=conform;radicalli=radic;differentli=differ;vileli=vile;"
            + "analogousli=analog;vietnamization=vietnam;predication=predic;operator=oper;feudalism=feudal;"
            + "decisiveness=decis;hopefulness=hope;callousness=callous;formaliti=formal;sensitiviti=sensit;"
            + "sensibiliti=sensibl;triplicate=triplic;formative=form;formalize=formal;electriciti=electr;"
            + "electrical=electr;hopeful=hope;goodness=good;revival=reviv;allowance=allow;inference=infer;"
            + "airliner=airlin;gyroscopic=gyroscop;adjustable=adjust;defensible=defens;irritant=irrit;"
            + "replacement=replac;adjustment=adjust;dependent=depend;adoption=adopt;homologou=homolog;"
            + "communism=commun;activate=activ;angulariti=angular;homologous=homolog;effective=effect;"
            + "bowdlerize=bowdler;probate=probat;rate=rate;cease=ceas;controll=control;roll=roll;"
            + "generalizations=gener;oscillators=oscil;customers=custom;quantities=quantiti;identifiers=identifi;"
            + "departments=depart;employees=employe;addresses=address;relationships=relationship;"
            + "descriptions=descript";

    @Test
    void porterMatchesNltk() {
        for (String pair : PORTER_REFERENCE.split(";")) {
            String[] kv = pair.split("=");
            assertEquals(kv[1], PorterStemmer.stem(kv[0]), kv[0]);
        }
    }

    @Test
    void alignment() {
        assertEquals(1.0f, new Alignment(Alignment.SMITH_WATERMAN).compare("abc", "xxabcxx"), EPS);
        assertEquals(1.0f, new Alignment(Alignment.NEEDLEMAN_WUNSCH).compare("Name", "name"), EPS);
        // NW edit cost of qty -> quantity: 5 gaps of cost 2 = 10, over max length 8 * 2
        assertEquals(1.0 - 10.0 / 16.0, new Alignment(Alignment.NEEDLEMAN_WUNSCH).compare("qty", "quantity"), EPS);
        // SW bridges "abc....def" with 4 linear gaps: 3 - 2 + 3 = 4; Gotoh with one affine gap:
        // 3 - (1 + 3 * 0.25) + 3 = 4.25; both over min length 6
        assertEquals(4.0 / 6.0, new Alignment(Alignment.SMITH_WATERMAN).compare("abcdef", "abcxxxxdef"), EPS);
        assertEquals(4.25 / 6.0, new Alignment(Alignment.SMITH_WATERMAN_GOTOH).compare("abcdef", "abcxxxxdef"), EPS);
    }

    @Test
    void thesaurusAndNorms() {
        Path file = Path.of(System.getProperty("user.dir")).getParent().getParent()
                .resolve("sf-matcher-study").resolve("resources").resolve("thesaurus_generic.csv");
        if (!Files.isRegularFile(file)) {
            return; // the example file lives in the study folder next to code/
        }
        ThesaurusMatcher t = new ThesaurusMatcher();
        t.setThesaurusFile(file.toString());
        TablePair tp = labelsOnly(List.of("customer_id", "zip_code", "salary"),
                List.of("client_identifier", "postcode", "wage"));
        float[][] s = t.match(tp);
        assertEquals(1.0f, s[0][0], EPS);
        assertEquals(1.0f, s[1][1], EPS);
        assertEquals(1.0f, s[2][2], EPS);
        assertTrue(s[0][2] < 0.9f);

        NormsMatcher n = new NormsMatcher();
        float[][] a = n.match(labelsOnly(List.of("cust_nm", "ord_dt"), List.of("customer_name", "order_date")));
        assertTrue(a[0][0] > 0.95f && a[1][1] > 0.95f, a[0][0] + " " + a[1][1]);
        assertTrue(a[0][0] > a[0][1]);
    }

    static TablePair numeric() {
        Map<String, List<String>> s = new LinkedHashMap<>();
        List<String> small = new ArrayList<>();
        List<String> skew = new ArrayList<>();
        for (int k = 1; k <= 10; k++) {
            small.add(Integer.toString(k));
            skew.add(Integer.toString(k * k * k));
        }
        s.put("a", small);
        s.put("phone", List.of("+49 6421 28 21500", "+49 6421 28 21511", "+49 6421 28 21522"));
        Map<String, List<String>> t = new LinkedHashMap<>();
        List<String> scaled = new ArrayList<>();
        for (int k = 1; k <= 10; k++) {
            scaled.add(Integer.toString(k * 100));
        }
        t.put("x", scaled);
        t.put("y", skew);
        t.put("tel", List.of("+49 30 2093 0", "+49 89 2180 0"));
        t.put("word", List.of("alpha", "beta", "gamma"));
        return new TablePair(table("s", s), table("t", t));
    }

    @Test
    void shapeCharactersSemInt() {
        TablePair tp = numeric();
        float[][] d = new DistributionShapeMatcher().match(tp);
        assertEquals(1.0f, d[0][0], 1e-5f);              // 1..10 and 100..1000: same shape
        assertTrue(d[0][1] < d[0][0]);                    // cubes are skewed
        float[][] c = new CharDistributionMatcher().match(tp);
        assertTrue(c[1][2] > c[1][3], c[1][2] + " " + c[1][3]); // phone ~ tel, not words
        float[][] si = new SemIntMatcher().match(tp);
        assertTrue(si[1][2] > si[1][3]);
    }

    @Test
    void unionability() {
        Map<String, List<String>> s = new LinkedHashMap<>();
        s.put("c", List.of("a", "b", "c", "d"));
        s.put("other", List.of("p", "q"));
        Map<String, List<String>> t = new LinkedHashMap<>();
        t.put("c2", List.of("a", "b", "c", "e"));
        t.put("z", List.of("w", "x", "y"));
        TablePair tp = new TablePair(table("s", s), table("t", t));
        float[][] u = new UnionabilityMatcher().match(tp);
        assertTrue(u[0][0] > 0.9f, "" + u[0][0]);
        assertEquals(0.0f, u[0][1], EPS);                 // no overlap: no evidence
        assertEquals(1.0, UnionabilityMatcherAccess.cdf(4, 10, 4, 4), 1e-12);
    }

    /** Package bridge to the matcher's static hypergeometric CDF. */
    static final class UnionabilityMatcherAccess {
        static double cdf(int x, int n, int k, int d) {
            try {
                java.lang.reflect.Method m = UnionabilityMatcher.class
                        .getDeclaredMethod("hypergeometricCdf", int.class, int.class, int.class, int.class);
                m.setAccessible(true);
                return (double) m.invoke(null, x, n, k, d);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    void murtyAgreesWithBruteForce() {
        double[][] s = {{0.9, 0.2, 0.4}, {0.3, 0.8, 0.5}, {0.1, 0.6, 0.7}};
        List<Assignments.Solution> top = Assignments.kBest(s, 6);
        assertEquals(6, top.size());                     // 3! permutations, all distinct
        List<Double> brute = new ArrayList<>();
        int[][] perms = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
        for (int[] p : perms) {
            brute.add(s[0][p[0]] + s[1][p[1]] + s[2][p[2]]);
        }
        brute.sort((a, b) -> Double.compare(b, a));
        for (int k = 0; k < 6; k++) {
            assertEquals(brute.get(k), top.get(k).score(), 1e-9);
        }
        // rectangular: 2 rows, 3 columns
        List<Assignments.Solution> rect = Assignments.kBest(new double[][]{{0.9, 0.1, 0.5}, {0.2, 0.8, 0.7}}, 3);
        assertEquals(0.9 + 0.8, rect.get(0).score(), 1e-9);
        assertEquals(0.9 + 0.7, rect.get(1).score(), 1e-9);
    }

    @Test
    void compositeAndProbabilisticConfigure() throws Exception {
        MatcherFactory f = new MatcherFactory();
        String components = "[{\"name\": \"LevenshteinMatcher\", \"package\": \"similarity.label\"},"
                + " {\"name\": \"QGramMatcher\", \"package\": \"similarity.label\", \"params\": {\"q\": 2}}]";
        for (Object[] c : new Object[][]{
                {"CompositeMatcher", "ensemble", Map.of("components", components, "aggregation", "PREDICTOR")},
                {"ProbabilisticMappingMatcher", "ensemble", Map.of("k", 5, "temperature", 0.05)},
                {"AlignmentMatcher", "similarity.label", Map.of("algorithm", "SMITH_WATERMAN_GOTOH")},
                {"NormsMatcher", "similarity.label", Map.of("maxAbbreviation", 5)},
                {"UnionabilityMatcher", "similarity.instance", Map.of("domain", "PAIR")},
                {"DistributionShapeMatcher", "similarity.instance", Map.of("quantiles", 10)},
                {"CharDistributionMatcher", "similarity.instance", Map.of()},
                {"SemIntMatcher", "metadata", Map.of()},
                {"EmbdiMatcher", "sota", Map.of("serverPort", 5001)}}) {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = new HashMap<>((Map<String, Object>) c[2]);
            Matcher m = f.createMatcherInstance(new Configuration.MatcherConfiguration((String) c[0], (String) c[1], params));
            assertEquals(c[0], m.getClass().getSimpleName());
        }
    }
}
