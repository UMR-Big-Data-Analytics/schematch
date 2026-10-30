package de.uni_marburg.schematch;

import de.uni_marburg.schematch.data.Dataset;
import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.utils.Configuration;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class TestUtils {
    private static TestUtils.TestData testData = null;

    @Data
    @AllArgsConstructor
    public static class TestData {
        private Dataset dataset;
        private Map<String, Scenario> scenarios;
    }

    public static TestData getTestData() {
        if (TestUtils.testData == null) {
            TestUtils.testData = TestUtils.loadData();
        }
        return TestUtils.testData;
    }

    private static TestData loadData() {
        Path root;
        try {
            root = Files.createTempDirectory("schematch-test-fixtures-");
            root.toFile().deleteOnExit();
            for (String name : new String[]{"test1","test2","test3"}) {
                Path scenario = root.resolve(name);
                String source = "aid,name\n1,Alice\n2,Bob\n3,Clara\n";
                String target = "aid,name\n1,Alice\n2,Robert\n3,Clara\n";
                if (name.equals("test2")) {
                    source = "id,name,city,score,active,verified,birth,location\n"
                            + "1,Alice,Berlin,1.5,true,1,01-02,\"52.5200,13.4050\"\n"
                            + "2,Bob,Paris,2.75,false,0,03/04,\"48.8566,2.3522\"\n"
                            + "3,Clara,Rome,3.0,yes,no,05.06,\"41.9028,12.4964\"\n";
                    target = "id,name,date\n1,Alice,not-a-date\n2,Bob,03/04\n3,Clara,05.06\n";
                } else if (name.equals("test3")) {
                    source = target = "c1,c2,c3,c4\na,b,c,d\ne,f,g,h\n";
                }
                write(scenario.resolve("source/authors.csv"),source);
                write(scenario.resolve("target/authors.csv"),target);
                for (String side : new String[]{"source","target"}) {
                    Path metadata = scenario.resolve("metadata").resolve(side);
                    write(metadata.resolve("inds.txt"),"");
                    write(metadata.resolve("authors/FD_results.txt"),name.equals("test1")
                            ? "[authors.aid] --> authors.name\n[authors.name] --> authors.aid\n" : "");
                    write(metadata.resolve("authors/UCC_results.txt"),name.equals("test1")
                            ? "[authors.aid]\n[authors.name]\n" : "");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create local test fixtures",e);
        }
        Configuration.DatasetConfiguration dsConfig = new Configuration.DatasetConfiguration("unit-fixtures",root.toString());
        Dataset d = new Dataset(dsConfig);
        HashMap<String, Scenario> s = new HashMap<>();
        for (String sName : d.getScenarioNames()) {
            s.put(sName, new Scenario(d.getPath() + File.separator + sName));
        }
        return new TestData(d, s);
    }

    private static void write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path,value);
        path.toFile().deleteOnExit();
    }

}
