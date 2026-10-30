package de.uni_marburg.schematch.matching.similarityFlooding;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.uni_marburg.schematch.data.Database;
import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.data.metadata.Datatype;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in parity with the SF study on Valentine scenarios: -Dschematch.test.valentineRoot=&lt;directory with
 * Valentine-layout datasets and FD/UCC/IND profiles&gt; (dataset/scenario/{source,target,metadata}), as for
 * OneStepTermsTest. Skipped without it; the scenarios are not bundled.
 *
 * <p>The bundled JSON files (sf_parity/) hold, per scenario, the study's initial matrices (levenshtein, coma, pmf),
 * its SF outputs and its edge files, copied unchanged from the study's caches: SF as published (layer 1
 * levenshtein__melnik), two updates (iter levenshtein__melnik_t2), the standard configuration (the seed study's
 * levenshtein run), the second initial matrix X60 (two_coma__pmf__{melnik,fd}), the class files of the full panel
 * (classfull, shape and semtype, real and shuffled), the within-table edges of f6 (knn1-3, rnd2, knn2_rnd) and X62
 * (levenshtein). Every comparison is exact: bit-identical floats, identical edge lines.
 */
class SfParityTest {
    @TempDir Path dir;

    private static final String[] CASES = {"Magellan-Unionable__dblp_acm",
            "ChEMBL-Semantically-Joinable__assays_both_50_1_ac1_av", "TPC-DI-Unionable__prospect_horizontal_0_ac1_av"};

    private static Path root() {
        String root = System.getProperty("schematch.test.valentineRoot", "");
        Assumptions.assumeTrue(!root.isBlank(), "set schematch.test.valentineRoot to run the SF parity checks");
        return Path.of(root);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> record(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("sf_parity/" + name + ".json")) {
            assertNotNull(in, name);
            return new ObjectMapper().readValue(in, Map.class);
        }
    }

    private Scenario scenario(Path root, Map<String, Object> rec, boolean dependencies) {
        Path path = root.resolve((String) rec.get("dataset")).resolve((String) rec.get("scenario"));
        Assumptions.assumeTrue(Files.isDirectory(path), "missing " + path);
        return SfTestSupport.load(path, dependencies);
    }

    /** The seed directory of one bundled initial matrix. */
    @SuppressWarnings("unchecked")
    private String seeds(Map<String, Object> rec, String matcher) throws Exception {
        Path base = dir.resolve(matcher);
        String csv = ((Map<String, String>) rec.get("seeds")).get(matcher);
        return SfTestSupport.writeSeed(base, (String) rec.get("dataset"), (String) rec.get("scenario"), csv).toString();
    }

    private static void same(float[][] expected, float[][] actual, String what) {
        assertEquals(expected.length, actual.length, what);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i].length, actual[i].length, what);
            for (int j = 0; j < expected[i].length; j++) {
                assertEquals(Float.floatToIntBits(expected[i][j]), Float.floatToIntBits(actual[i][j]),
                        what + " cell " + i + "," + j + ": " + expected[i][j] + " vs " + actual[i][j]);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void presetsAndTheSecondInitialMatrixReproduceTheStudyOutputs() throws Exception {
        Path root = root();
        for (String name : CASES) {
            Map<String, Object> rec = record(name);
            Map<String, String> expected = (Map<String, String>) rec.get("expected");
            Scenario scenario = scenario(root, rec, true);
            String levenshtein = seeds(rec, "levenshtein"), coma = seeds(rec, "coma"), pmf = seeds(rec, "pmf");
            Map<String, Supplier<SimilarityFlooding>> arms = new LinkedHashMap<>();
            arms.put("published", SimilarityFloodingPublished::new);
            arms.put("two_updates", SimilarityFloodingTwoUpdates::new);
            arms.put("standard", SimilarityFloodingStandard::new);
            for (Map.Entry<String, Supplier<SimilarityFlooding>> arm : arms.entrySet()) {
                SimilarityFlooding sf = arm.getValue().get();
                sf.setInitialMatrix("FILE");
                sf.setInitialMatrixDir(levenshtein);
                same(SfTestSupport.matrix(expected.get(arm.getKey())), SfTestSupport.run(sf, scenario), name + " " + arm.getKey());
            }
            for (String graph : List.of("melnik", "fd")) {
                SimilarityFlooding sf = new SimilarityFloodingPublished();
                sf.setInitialMatrix("FILE");
                sf.setInitialMatrixDir(coma);
                sf.setIdMatrixDir(pmf);
                if (graph.equals("fd")) sf.setFdFilterThreshold("0.0");
                same(SfTestSupport.matrix(expected.get("x60_" + graph)), SfTestSupport.run(sf, scenario), name + " X60 " + graph);
            }
        }
    }

    /** The native generator's lines for one variant of the bundled edge files. */
    private static List<String> generated(String variant, Map<String, Object> rec, String side, Database db) {
        String dataset = (String) rec.get("dataset"), scenario = (String) rec.get("scenario");
        if (variant.startsWith("class/")) {
            String v = variant.substring("class/".length());
            List<String> out = new ArrayList<>();
            for (Table table : db.getTables()) {
                out.addAll(ColumnClasses.edgeLines(dataset, scenario, side, table,
                        v.startsWith("shape") ? ColumnClasses.Kind.SHAPE : ColumnClasses.Kind.SEMTYPE, v.endsWith("_shuffled")));
            }
            return out;
        }
        WithinTableEdges.Spec spec = switch (variant) {
            case "f6/knn1" -> WithinTableEdges.Spec.of("KNN", null, "1", null, null, null);
            case "f6/knn2" -> WithinTableEdges.Spec.of("KNN", null, "2", null, null, null);
            case "f6/knn3" -> WithinTableEdges.Spec.of("KNN", null, "3", null, null, null);
            case "f6/rnd2" -> WithinTableEdges.Spec.of("RANDOM", null, "2", null, null, null);
            case "f6/knn2_rnd" -> WithinTableEdges.Spec.of("KNN", null, "2", "RANK", null, null);
            case "x62/levenshtein" -> WithinTableEdges.Spec.of("KNN", "similarity.label.LevenshteinMatcher", "2", "DRAW",
                    "x62|plain|levenshtein", null);
            default -> throw new IllegalArgumentException(variant);
        };
        return WithinTableEdges.lines(spec, dataset, scenario, side, db);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nativeClassNodesAndWithinTableEdgesEqualTheStudyFiles() throws Exception {
        Path root = root();
        for (String name : CASES) {
            Map<String, Object> rec = record(name);
            Scenario scenario = scenario(root, rec, false);
            Map<String, Map<String, List<String>>> edges = (Map<String, Map<String, List<String>>>) rec.get("edges");
            assertEquals(10, edges.size());
            for (Map.Entry<String, Map<String, List<String>>> variant : edges.entrySet()) {
                for (String side : List.of("source", "target")) {
                    Database db = side.equals("source") ? scenario.getSourceDatabase() : scenario.getTargetDatabase();
                    assertEquals(variant.getValue().get(side), generated(variant.getKey(), rec, side, db),
                            name + " " + variant.getKey() + " " + side);
                }
            }
        }
    }

    /** A generated line builds the graph the same line in an extraEdgesDir file builds. */
    @Test
    @SuppressWarnings("unchecked")
    void nativeEdgesRunLikeTheirFiles() throws Exception {
        Path root = root();
        Map<String, Object> rec = record(CASES[0]);
        Scenario scenario = scenario(root, rec, false);
        String levenshtein = seeds(rec, "levenshtein");
        Map<String, Map<String, List<String>>> edges = (Map<String, Map<String, List<String>>>) rec.get("edges");
        Map<String, String[]> arms = Map.of(
                "class/semtype_shuffled", new String[]{"classNodes", "SEMTYPE_SHUFFLED"},
                "f6/knn2_rnd", new String[]{"withinTableEdges", "KNN", "withinTableTieBreak", "RANK"},
                "f6/rnd2", new String[]{"withinTableEdges", "RANDOM"});
        for (Map.Entry<String, String[]> arm : arms.entrySet()) {
            Path files = dir.resolve("edges").resolve(arm.getKey().replace('/', '_'));
            for (String side : List.of("source", "target")) {
                Path f = files.resolve((String) rec.get("dataset")).resolve((String) rec.get("scenario")).resolve(side + ".tsv");
                Files.createDirectories(f.getParent());
                Files.write(f, edges.get(arm.getKey()).get(side));
            }
            SimilarityFlooding fromFile = new SimilarityFloodingPublished(), generatedArm = new SimilarityFloodingPublished();
            for (SimilarityFlooding sf : List.of(fromFile, generatedArm)) {
                sf.setInitialMatrix("FILE");
                sf.setInitialMatrixDir(levenshtein);
            }
            fromFile.setExtraEdgesDir(files.toString());
            String[] p = arm.getValue();
            for (int k = 0; k < p.length; k += 2) {
                SimilarityFlooding.class.getMethod("set" + Character.toUpperCase(p[k].charAt(0)) + p[k].substring(1), String.class)
                        .invoke(generatedArm, p[k + 1]);
            }
            float[][] a = SfTestSupport.run(fromFile, scenario), b = SfTestSupport.run(generatedArm, scenario);
            same(a, b, arm.getKey());
            SimilarityFlooding plain = new SimilarityFloodingPublished();
            plain.setInitialMatrix("FILE");
            plain.setInitialMatrixDir(levenshtein);
            assertFalse(java.util.Arrays.deepEquals(a, SfTestSupport.run(plain, scenario)), arm.getKey() + " changes SF");
        }
    }

    /**
     * Proposition 1 on Valentine's single tables: without dependency edges, SF never inverts the order of the initial
     * scores inside a datatype-pair block, at any finite update and converged (reference/check_prop1.py criterion:
     * q higher by more than 1e-9 and SF lower by more than 1e-6).
     */
    @Test
    void propositionOneHoldsOnValentine() throws Exception {
        Path root = root();
        for (String name : CASES) {
            Map<String, Object> rec = record(name);
            Scenario scenario = scenario(root, rec, false);
            float[][] q = SfTestSupport.matrix(((Map<?, ?>) rec.get("seeds")).get("levenshtein").toString());
            String levenshtein = seeds(rec, "levenshtein");
            for (String policy : List.of("INV_AVG", "INV_PROD")) {
                for (String attach : List.of("NAME", "ID", "SPLIT")) {
                    for (String cap : List.of("0", "1", "2", "4", "200")) {
                        SimilarityFlooding sf = new SimilarityFloodingPublished();
                        sf.setInitialMatrix("FILE");
                        sf.setInitialMatrixDir(levenshtein);
                        sf.setPropCoeffPolicy(policy);
                        sf.setSeedAttachment(attach);
                        sf.setMaxIterations(cap);
                        float[][] s = SfTestSupport.run(sf, scenario);
                        assertEquals(0, PropositionOneTest.inversions(q, s, datatypes(scenario.getSourceDatabase()),
                                datatypes(scenario.getTargetDatabase()), 1e-9, 1e-6), name + " " + policy + " " + attach + " " + cap);
                    }
                }
            }
        }
    }

    static Datatype[] datatypes(Database db) {
        return db.getColumns().stream().map(c -> c.getDatatype()).toArray(Datatype[]::new);
    }
}
