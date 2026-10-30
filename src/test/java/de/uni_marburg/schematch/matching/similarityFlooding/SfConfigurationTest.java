package de.uni_marburg.schematch.matching.similarityFlooding;

import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.matching.MatcherFactory;
import de.uni_marburg.schematch.utils.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** SF's per-instance parameters, presets, native class and within-table edges, and the study RNG ports. */
class SfConfigurationTest {
    @TempDir Path dir;

    @Test
    void unsetParametersStayOutOfTheResultIdentity() {
        SimilarityFlooding sf = new SimilarityFlooding();
        String plain = sf.toString().replaceAll("@[0-9a-f]+", "");
        assertFalse(plain.contains("epsilon="), plain);
        assertFalse(plain.contains("classNodes="), plain);
        assertTrue(plain.endsWith("initialMatrixDir= &  relationalNullMaskDir=)"), plain);
        sf.setEpsilon("0.05");
        assertTrue(sf.toString().endsWith("relationalNullMaskDir= &  epsilon=0.05)"), sf.toString());
    }

    @Test
    void presetsSetTheStudyConfigurations() throws Exception {
        SimilarityFloodingPublished published = new SimilarityFloodingPublished();
        assertEquals(List.of("QUICK", "INV_AVG", "C", "", "NONE", "NAME", "PREFIX_SUFFIX", "0.05", "200"),
                List.of(published.getGraphEncoding(), published.getPropCoeffPolicy(), published.getFixpoint(),
                        published.getFdFilterThreshold(), published.getConstraintWeighting(), published.getSeedAttachment(),
                        published.getLiteralSeed(), published.getEpsilon(), published.getMaxIterations()));
        SimilarityFloodingStandard standard = new SimilarityFloodingStandard();
        assertEquals(List.of("QUICK", "INV_PROD", "C", "0.0", "NONE", "NAME", "NONE", "0.0001"),
                List.of(standard.getGraphEncoding(), standard.getPropCoeffPolicy(), standard.getFixpoint(),
                        standard.getFdFilterThreshold(), standard.getConstraintWeighting(), standard.getSeedAttachment(),
                        standard.getLiteralSeed(), standard.getEpsilon()));
        assertEquals("1", new SimilarityFloodingTwoUpdates().getMaxIterations());
        assertEquals("0.05", new SimilarityFloodingTwoUpdates().getEpsilon());
        assertTrue(new SimilarityFloodingTwoUpdates().toString().startsWith("SimilarityFloodingTwoUpdates(LABEL_SCORE_WEIGHT"));
        // YAML numbers reach the string parameters as their text, through the same factory path as a configuration.
        SimilarityFlooding sf = (SimilarityFlooding) new MatcherFactory().createMatcherInstance(new Configuration.MatcherConfiguration(
                "SimilarityFloodingPublished", "similarityFlooding", Map.of("epsilon", 0.01, "maxIterations", 3, "tableRooted", true)));
        assertEquals(List.of("0.01", "3", "true"), List.of(sf.getEpsilon(), sf.getMaxIterations(), sf.getTableRooted()));
    }

    private Scenario scenario() throws Exception {
        Files.createDirectories(dir.resolve("data/case/source"));
        Files.createDirectories(dir.resolve("data/case/target"));
        Files.writeString(dir.resolve("data/case/source/s.csv"), "id,code,price,mail,note\n"
                + "1,AB-12,1.5,a@b.de,one two three four\n2,CD-34,2.25,c@d.org,five six seven eight\n"
                + "3,EF-56,3.0,e@f.com,x\n4,GH-78,3.0,g@h.net,y z\n");
        Files.writeString(dir.resolve("data/case/target/t.csv"), "key,ref,cost,email,text\n"
                + "7,XY-99,9.5,p@q.de,a b c d\n8,ZZ-11,1.25,r@s.org,e f g h\n9,QQ-22,0.5,t@u.com,i\n");
        return SfTestSupport.load(dir.resolve("data/case"), false);
    }

    private String seeds(Scenario scenario) throws Exception {
        StringBuilder csv = new StringBuilder("Source\\Target");
        int n = scenario.getSourceDatabase().getNumColumns(), m = scenario.getTargetDatabase().getNumColumns();
        for (int j = 0; j < m; j++) csv.append(',').append(scenario.getTargetDatabase().getFullColumnNameByIndex(j));
        csv.append('\n');
        for (int i = 0; i < n; i++) {
            csv.append(scenario.getSourceDatabase().getFullColumnNameByIndex(i));
            for (int j = 0; j < m; j++) csv.append(',').append(i == j ? 0.9f : (i + 2 * j) % 5 / 10f);
            csv.append('\n');
        }
        return SfTestSupport.writeSeed(dir.resolve("seeds"), "data", "case", csv.toString()).toString();
    }

    private SimilarityFlooding published(String seeds) {
        SimilarityFlooding sf = new SimilarityFloodingPublished();
        sf.setInitialMatrix("FILE");
        sf.setInitialMatrixDir(seeds);
        return sf;
    }

    @Test
    void configurationsRunSideBySideInOneJvm() throws Exception {
        Scenario scenario = scenario();
        String seeds = seeds(scenario);
        float[][] converged = SfTestSupport.run(published(seeds), scenario);
        SimilarityFlooding two = new SimilarityFloodingTwoUpdates();
        two.setInitialMatrix("FILE");
        two.setInitialMatrixDir(seeds);
        float[][] twoUpdates = SfTestSupport.run(two, scenario);
        SimilarityFlooding byHand = published(seeds);
        byHand.setMaxIterations("1");
        assertArrayEquals(twoUpdates, SfTestSupport.run(byHand, scenario));
        assertFalse(Arrays.deepEquals(converged, twoUpdates));
        assertArrayEquals(converged, SfTestSupport.run(published(seeds), scenario));  // nothing leaks between runs
        // The second initial matrix: the same matrix on the ID pairs changes the run; it needs NAME attachment.
        SimilarityFlooding x60 = published(seeds);
        x60.setIdMatrixDir(seeds);
        assertFalse(Arrays.deepEquals(converged, SfTestSupport.run(x60, scenario)));
        x60.setSeedAttachment("ID");
        assertThrows(IllegalArgumentException.class, () -> SfTestSupport.run(x60, scenario));
        SimilarityFlooding bad = published(seeds);
        bad.setRestartAlpha("1.0");
        assertThrows(IllegalArgumentException.class, () -> SfTestSupport.run(bad, scenario));
        bad.setRestartAlpha(null);
        bad.setLiteralSeed("SOMETIMES");
        assertThrows(IllegalArgumentException.class, () -> SfTestSupport.run(bad, scenario));
        bad.setLiteralSeed(null);
        bad.setClassNodes("SHAPE,SHAPE_SHUFFLED");
        assertThrows(IllegalArgumentException.class, () -> SfTestSupport.run(bad, scenario));
    }

    @Test
    void generatedEdgesBuildTheGraphTheirFileBuilds() throws Exception {
        Scenario scenario = scenario();
        String seeds = seeds(scenario);
        for (String[] arm : new String[][]{{"classNodes", "SEMTYPE"}, {"classNodes", "SHAPE_SHUFFLED"},
                {"withinTableEdges", "KNN"}, {"withinTableEdges", "RANDOM"}}) {
            GraphOptions options = GraphOptions.resolve(Map.of(arm[0], arm[1]));
            Path files = dir.resolve("edges").resolve(arm[1]);
            for (String side : List.of("source", "target")) {
                var db = side.equals("source") ? scenario.getSourceDatabase() : scenario.getTargetDatabase();
                List<String> lines = new ArrayList<>();
                for (String kind : options.classNodes) {
                    lines.addAll(ColumnClasses.edgeLines("data", "case", side, db.getTables().get(0),
                            ColumnClasses.Kind.valueOf(kind.replace("_SHUFFLED", "")), kind.endsWith("_SHUFFLED")));
                }
                lines.addAll(WithinTableEdges.lines(options.withinTable, "data", "case", side, db));
                assertFalse(lines.isEmpty(), arm[1]);
                Files.createDirectories(files.resolve("data/case"));
                Files.write(files.resolve("data/case").resolve(side + ".tsv"), lines);
            }
            SimilarityFlooding fromFile = published(seeds), generated = published(seeds);
            fromFile.setExtraEdgesDir(files.toString());
            if (arm[0].equals("classNodes")) generated.setClassNodes(arm[1]);
            else generated.setWithinTableEdges(arm[1]);
            float[][] a = SfTestSupport.run(fromFile, scenario);
            assertArrayEquals(a, SfTestSupport.run(generated, scenario), arm[1]);
            assertFalse(Arrays.deepEquals(a, SfTestSupport.run(published(seeds), scenario)), arm[1]);
        }
    }

    @Test
    void semanticTypesFollowTheStudyRules() {
        assertEquals("boolean", ColumnClasses.semanticType(" yes "));
        assertEquals("email", ColumnClasses.semanticType("a.b@c.org"));
        assertEquals("url", ColumnClasses.semanticType("https://x.org/a"));
        assertEquals("date", ColumnClasses.semanticType("2020-1-2"));
        assertEquals("date", ColumnClasses.semanticType("20200102"));
        assertEquals("integer", ColumnClasses.semanticType("12345678"));   // eight digits, not a calendar date
        assertEquals("year", ColumnClasses.semanticType("1999"));
        assertEquals("currency", ColumnClasses.semanticType("\u20ac 1,250.00"));
        assertEquals("float", ColumnClasses.semanticType("-.5e3"));
        assertEquals("phone", ColumnClasses.semanticType("+49 6421 28-0"));
        assertEquals("phone", ColumnClasses.semanticType("12345-6789"));   // phone is tried before postal
        assertEquals("postal", ColumnClasses.semanticType("K1A 0B1"));
        assertEquals("country", ColumnClasses.semanticType("New Zealand"));
        assertEquals("us_state", ColumnClasses.semanticType("NY"));
        assertEquals("code", ColumnClasses.semanticType("AB_12"));
        assertEquals("name_like", ColumnClasses.semanticType("Anna O\u2019hara"));
        assertEquals("text", ColumnClasses.semanticType("four words in here"));
        assertEquals("other", ColumnClasses.semanticType("mixed Case"));
        assertEquals("integer_", ColumnClasses.semanticTypeClass(List.of("1", "2", "x", " ", "")));  // 2 of 3 >= 60%
        assertEquals("mixed_", ColumnClasses.semanticTypeClass(List.of("1", "x")));
        assertEquals("mixed_", ColumnClasses.semanticTypeClass(List.of("", " ")));
    }

    /** Reference values from CPython 3.10 random and NumPy 2.2 (scratch script, 2026-10-30). */
    @Test
    void randomNumberGeneratorsReproducePythonAndNumpy() {
        StudyRandom.Python py = new StudyRandom.Python("abc");
        assertEquals(List.of(3315820543L, 4246262336L, 2397318194L),
                List.of(Integer.toUnsignedLong(py.nextInt32()), Integer.toUnsignedLong(py.nextInt32()),
                        Integer.toUnsignedLong(py.nextInt32())));
        List<Integer> x = new ArrayList<>(IntStream.range(0, 10).boxed().toList());
        new StudyRandom.Python(StudyRandom.sha256Hex("d/s/source")).shuffle(x);
        assertEquals(List.of(9, 3, 1, 4, 6, 8, 5, 2, 0, 7), x);
        StudyRandom.Python sampler = new StudyRandom.Python(StudyRandom.sha256Hex("d/s/source"));
        List<List<Integer>> samples = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int skip = i;
            samples.add(sampler.sample(IntStream.range(0, 30).filter(j -> j != skip).boxed().toList(), 2));
        }
        assertEquals(List.of(List.of(16, 21), List.of(2, 25), List.of(27, 28)), samples);
        StudyRandom.Numpy np = new StudyRandom.Numpy(12345);
        assertEquals(0.22733602246716966, np.random());
        assertEquals(0.31675833970975287, np.random());
        assertEquals(0.7973654573327341, np.random());
        assertArrayEquals(new int[]{1, 7, 9, 8, 2, 4, 5, 3, 0, 6}, WithinTableEdges.tieRanks(10, "d/s/source/tiebreak"));
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update("x62|plain|m|d|s|source|0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StudyRandom.Numpy draw = new StudyRandom.Numpy(BigInteger.valueOf(crc.getValue()));
        assertEquals(0.9029805781106649, draw.random());
        assertEquals(0.6170950992407275, draw.random());
    }

    @Test
    void numpySumsInBufferChunks() {
        double[] a = new double[11250];
        Arrays.fill(a, 1.0 / 11250);
        assertEquals(0.9999999999999998, WithinTableEdges.numpySum(a));   // np.full(11250, 1/11250).sum()
        assertEquals(1.0000000000000002, WithinTableEdges.pairwiseSum(a, 0, a.length));
    }
}
