package de.uni_marburg.schematch.utils;

import de.uni_marburg.schematch.data.*;
import de.uni_marburg.schematch.matchtask.MatchTask;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import de.uni_marburg.schematch.matchtask.matchstep.MatchingStep;
import de.uni_marburg.schematch.matching.external.ComaMatcher;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DatasetCompatibilityTest {
    @TempDir Path dir;

    private Scenario scenario() throws Exception {
        Files.createDirectories(dir.resolve("source")); Files.createDirectories(dir.resolve("target"));
        Files.writeString(dir.resolve("source/b.csv"),"code\n001\n");
        Files.writeString(dir.resolve("source/a.csv"),"\ufeffid,naïve name\n001,\"comma, newline\nvalue\"\n002,NA\n");
        Files.writeString(dir.resolve("source/README.txt"),"not a table");
        Files.writeString(dir.resolve("target/x.csv"),"key,title\n1,Äda\n");
        Configuration config = Configuration.getInstance();
        boolean old = config.isReadDependencies();
        try { config.setReadDependencies(false); return new Scenario(dir.toString()); }
        finally { config.setReadDependencies(old); }
    }

    @Test void normalReaderPreservesUtf8BomQuotedRowsAndStringValues() throws Exception {
        Scenario scenario = scenario();
        assertEquals(2,scenario.getSourceDatabase().getTables().size());
        Table table = scenario.getSourceDatabase().getTables().get(0);
        assertEquals(List.of("id","naïve name"),table.getLabels());
        assertEquals("001",table.getColumn(0).getValues().get(0));
        assertEquals("NA",table.getColumn(1).getValues().get(1));
        assertEquals("comma, newline\nvalue",table.getColumn(1).getValues().get(0));
        assertEquals(2,CsvTables.inspect(dir.resolve("source/a.csv"),",").rows());
        Files.writeString(dir.resolve("one.csv"),"value\n\n001\n");
        assertEquals(List.of("","001"),CsvTables.read(dir.resolve("one.csv"),",",null).getColumn(0).getValues());
    }

    @Test void labeledFullTruthAlignsReorderedColumnsAndPopulatesTableBlocks() throws Exception {
        Scenario scenario = scenario();
        Files.writeString(dir.resolve("ground_truth_full.csv"),"Source\\Target,x.title,x.key\nb.code,0,1\na.naïve name,1,0\na.id,0,1\n");
        int[][] truth = GroundTruthReader.read(scenario);
        assertArrayEquals(new int[]{1,0},truth[0]);
        assertArrayEquals(new int[]{0,1},truth[1]);
        assertArrayEquals(new int[]{1,0},truth[2]);
        MatchTask task = new MatchTask(null,scenario,List.of(),List.of());
        List<TablePair> pairs = new ArrayList<>();
        for (Table source : scenario.getSourceDatabase().getTables()) pairs.add(new TablePair(source,scenario.getTargetDatabase().getTables().get(0)));
        task.setTablePairs(pairs); task.readGroundTruth();
        assertArrayEquals(new int[]{1,0},pairs.get(1).getGroundTruth()[0]);
    }

    @Test void pairTruthSupportsZeroBlocksAndFullTruthMustAgree() throws Exception {
        Scenario scenario = scenario();
        Files.createDirectories(dir.resolve("ground_truth"));
        Files.writeString(dir.resolve("ground_truth/a___x.csv"),"1,0\n0,1\n");
        Files.writeString(dir.resolve("ground_truth/b___x.csv"),"0,0\n");
        assertEquals(2,ArrayUtils.sumOfMatrix(GroundTruthReader.read(scenario)));
        Files.writeString(dir.resolve("ground_truth_full.csv"),"1,0\n0,1\n0,1\n");
        assertThrows(IllegalArgumentException.class,() -> GroundTruthReader.read(scenario));
    }

    @Test void missingAndMalformedTruthFailInsteadOfBecomingAllNegatives() throws Exception {
        Scenario scenario = scenario();
        assertThrows(IllegalArgumentException.class,() -> GroundTruthReader.read(scenario));
        Files.writeString(dir.resolve("ground_truth_full.csv"),"0,1\n0\n1,0\n");
        assertThrows(IllegalArgumentException.class,() -> GroundTruthReader.read(scenario));
        Files.writeString(dir.resolve("ground_truth_full.csv"),"0,1\n0,2\n1,0\n");
        assertThrows(IllegalArgumentException.class,() -> GroundTruthReader.read(scenario));
        Files.writeString(dir.resolve("ground_truth_full.csv"),"s,x.key,x.title\na.id,1,0\na.id,0,1\nb.code,0,1\n");
        assertThrows(IllegalArgumentException.class,() -> GroundTruthReader.read(scenario));
    }

    @Test void duplicateHeadersAndRaggedTablesFailInBothAuditAndLoader() throws Exception {
        Path bad = dir.resolve("bad.csv");
        for (String contents : List.of("id,id\n1,2\n","id,name\n1\n","id,name\n1,a,extra\n")) {
            Files.writeString(bad,contents);
            assertThrows(IllegalArgumentException.class,() -> CsvTables.read(bad,",",null));
            assertThrows(IllegalArgumentException.class,() -> CsvTables.inspect(bad,","));
        }
    }

    @Test void datasetDiscoveryIgnoresOutputDirectoriesAndResolvesAbsolutePaths() throws Exception {
        Files.createDirectories(dir.resolve("case/source")); Files.createDirectories(dir.resolve("case/target"));
        Files.createDirectories(dir.resolve("_outputs")); Files.createDirectories(dir.resolve("metadata"));
        Dataset dataset = new Dataset(new Configuration.DatasetConfiguration("test",dir.toString()));
        assertEquals(List.of("case"),dataset.getScenarioNames());
        assertEquals(dir.toAbsolutePath().normalize(),Configuration.resolveDatasetPath("irrelevant",dir.toString()));
        assertEquals(dir.resolve("case").toAbsolutePath(),Configuration.resolveDatasetPath(dir.toString(),"case"));
        Files.createDirectories(dir.resolve("incomplete/source"));
        assertThrows(IllegalArgumentException.class,() -> new Dataset(new Configuration.DatasetConfiguration("test",dir.toString())));
    }

    @Test void dependencyReadingRemainsAWritableBooleanYamlProperty() {
        var yaml = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.Constructor(Configuration.class,
                new org.yaml.snakeyaml.LoaderOptions()));
        Configuration disabled = yaml.load("readDependencies: false\nrunSimMatrixBoostingOnFirstLineMatchers: true\n");
        assertFalse(disabled.isReadDependencies());
        Configuration enabled = yaml.load("readDependencies: true\n");
        assertTrue(enabled.isReadDependencies());
    }

    @Test void exportedLabelsAreQuotedUtf8AndCacheRoundTripsEveryRowWithSafeNames() throws Exception {
        Files.createDirectories(dir.resolve("source")); Files.createDirectories(dir.resolve("target"));
        Files.writeString(dir.resolve("source/s.csv"),"a,\"naïve, name\"\n1,x\n");
        Files.writeString(dir.resolve("target/t.csv"),"\"t, target\",last\nx,1\n");
        Configuration config = Configuration.getInstance();
        boolean oldDependencies = config.isReadDependencies(), oldVerbose = config.isSaveOutputVerbose(), oldPair = config.isSaveOutputPerTablePair();
        String oldCache = config.getCacheDir();
        try {
            config.setReadDependencies(false); config.setSaveOutputVerbose(true); config.setSaveOutputPerTablePair(true);
            config.setCacheDir(dir.resolve("cache").toString());
            Scenario scenario = new Scenario(dir.toString());
            Dataset dataset = new Dataset(); dataset.setName("fixture");
            ComaMatcher matcher = new ComaMatcher(); matcher.setOptions("{\"path\":\"F:/some/file\",\"text\":\""+"x".repeat(300)+"\"}");
            MatchingStep step = new MatchingStep(false,false,1,List.of(matcher));
            MatchTask task = new MatchTask(dataset,scenario,List.of(step),List.of());
            task.setTablePairs(List.of(new TablePair(scenario.getSourceDatabase().getTables().get(0),scenario.getTargetDatabase().getTables().get(0))));
            float[][] scores = {{.9f,.2f},{.3f,.8f}};
            Path out = dir.resolve("out");
            OutputWriter.writeSimMatrix(out,task,matcher.toString(),scores);
            Path csv = out.resolve(OutputWriter.toSafeFileName(matcher.toString())+".csv");
            try (var reader = CsvTables.reader(csv); CSVParser parser = new CSVParser(reader,CSVFormat.DEFAULT)) {
                var rows = parser.getRecords();
                assertEquals("t.t, target",rows.get(0).get(1));
                assertEquals("s.naïve, name",rows.get(2).get(0));
                assertEquals(3,rows.get(2).size());
            }
            Path cache = ResultsUtils.getCachePathForMatchStepInScenario(task,step);
            OutputWriter.writeSimMatrix(cache,task,matcher.toString(),scores);
            float[][] loaded = InputReader.readCache(task,step,matcher);
            assertArrayEquals(scores[0],loaded[0]); assertArrayEquals(scores[1],loaded[1]);
            Path cached = cache.resolve(OutputWriter.toSafeFileName(matcher.toString())+".csv");
            Files.writeString(cached,"0.9,0.2\n");
            assertThrows(IllegalStateException.class,() -> InputReader.readCache(task,step,matcher));
        } finally {
            config.setReadDependencies(oldDependencies); config.setSaveOutputVerbose(oldVerbose);
            config.setSaveOutputPerTablePair(oldPair); config.setCacheDir(oldCache);
        }
    }
}
