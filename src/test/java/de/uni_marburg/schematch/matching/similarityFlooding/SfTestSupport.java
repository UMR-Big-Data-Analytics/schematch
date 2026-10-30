package de.uni_marburg.schematch.matching.similarityFlooding;

import de.uni_marburg.schematch.data.Dataset;
import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.matchtask.MatchTask;
import de.uni_marburg.schematch.matchtask.matchstep.MatchStep;
import de.uni_marburg.schematch.matchtask.matchstep.MatchingStep;
import de.uni_marburg.schematch.utils.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Running SF on a scenario outside a pipeline, and reading Schematch's matrix files. */
final class SfTestSupport {
    private SfTestSupport() {}

    static Scenario load(Path path, boolean dependencies) {
        Configuration config = Configuration.getInstance();
        boolean old = config.isReadDependencies();
        try {
            config.setReadDependencies(dependencies);
            return new Scenario(path.toString());
        } finally {
            config.setReadDependencies(old);
        }
    }

    /** SF's output matrix; the initial matrix comes from initialMatrix=FILE. */
    static float[][] run(SimilarityFlooding sf, Scenario scenario) {
        Dataset dataset = new Dataset();
        dataset.setName(Path.of(scenario.getPath()).getParent().getFileName().toString());
        MatchingStep first = new MatchingStep(false, false, 1, List.of(sf));
        MatchingStep second = new MatchingStep(false, false, 2, List.of(sf));
        MatchTask task = new MatchTask(dataset, scenario, List.<MatchStep>of(first, second), List.of());
        return sf.match(task, second);
    }

    /** Writes &lt;root&gt;/&lt;dataset&gt;/&lt;scenario&gt;/seed.csv, the layout initialMatrix=FILE reads. */
    static Path writeSeed(Path root, String dataset, String scenario, String csv) throws Exception {
        Path file = root.resolve(dataset).resolve(scenario).resolve("seed.csv");
        Files.createDirectories(file.getParent());
        Files.writeString(file, csv);
        return root;
    }

    /** A Schematch matrix CSV (header row, label column; verbose "selected / score" cells read as the score). */
    static float[][] matrix(String csv) {
        List<String> lines = csv.lines().filter(l -> !l.isBlank()).toList();
        float[][] out = new float[lines.size() - 1][];
        for (int i = 1; i < lines.size(); i++) {
            String[] cells = lines.get(i).split(",", -1);
            out[i - 1] = new float[cells.length - 1];
            for (int j = 1; j < cells.length; j++) {
                String c = cells[j].trim();
                int slash = c.indexOf('/');
                out[i - 1][j - 1] = Float.parseFloat(slash >= 0 ? c.substring(slash + 1).trim() : c);
            }
        }
        return out;
    }
}
