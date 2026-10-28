package de.uni_marburg.schematch.utils;

import de.uni_marburg.schematch.data.Database;
import de.uni_marburg.schematch.data.Dataset;
import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.data.metadata.dependency.FunctionalDependency;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;

/**
 * Dumps the functional-dependency scores that {@code DependencyFilter} ranks by.
 *
 * The budget experiments select the top-k dependencies by ngpdep, so any
 * attempt to predict a good budget has to work from that same distribution.
 * Recomputing it outside the project would risk measuring a slightly different
 * quantity, so this reads it straight from the scoring code.
 *
 * Standalone entry point; it runs no matching and writes no results directory.
 * Datasets come from the usual {@code datasets.yaml}.
 *
 * <pre>java -cp ... de.uni_marburg.schematch.utils.DependencyScoreExporter out.csv</pre>
 */
public class DependencyScoreExporter {
    private static final Logger log = LogManager.getLogger(DependencyScoreExporter.class);

    public static void main(String[] args) throws IOException {
        Path output = Paths.get(args.length > 0 ? args[0] : "fd_scores.csv");
        Configuration config = Configuration.getInstance();

        Files.createDirectories(output.toAbsolutePath().getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(output)) {
            writer.write("dataset,scenario,side,signature,determinant_size,gpdep,ngpdep");
            writer.newLine();

            for (Configuration.DatasetConfiguration datasetConfiguration : config.getDatasetConfigurations()) {
                Dataset dataset = new Dataset(datasetConfiguration);
                log.info("Exporting FD scores for dataset {} ({} scenarios)",
                        dataset.getName(), dataset.getScenarioNames().size());

                for (String scenarioName : dataset.getScenarioNames()) {
                    Scenario scenario = new Scenario(dataset.getPath() + File.separator + scenarioName);
                    write(writer, dataset.getName(), scenarioName, "source", scenario.getSourceDatabase());
                    write(writer, dataset.getName(), scenarioName, "target", scenario.getTargetDatabase());
                }
            }
        }
        log.info("Wrote FD scores to {}", output.toAbsolutePath());
    }

    /** Same canonical form the allow-lists and SchemaGraphBuilder use. */
    private static String signatureOf(FunctionalDependency fd) {
        java.util.List<String> determinant = new java.util.ArrayList<>();
        for (de.uni_marburg.schematch.data.Column column : fd.getDeterminant()) {
            determinant.add(column.getTable().getName() + "." + column.getLabel());
        }
        java.util.Collections.sort(determinant);
        return String.join(",", determinant) + "->"
                + fd.getDependant().getTable().getName() + "." + fd.getDependant().getLabel();
    }

    private static void write(BufferedWriter writer, String dataset, String scenario,
                              String side, Database database) throws IOException {

        Collection<FunctionalDependency> fds =
                database.getMetadata().getMeaningfulFunctionalDependencies();

        for (FunctionalDependency fd : fds) {
            if (fd.getDeterminant().isEmpty() || fd.getDeterminant().size() > 3) {
                continue; // mirrors DependencyFilter, which never sees the rest
            }
            writer.write(dataset + "," + scenario + "," + side + ","
                    + "\"" + signatureOf(fd) + "\","
                    + fd.getDeterminant().size() + ","
                    + fd.calculateGPDEPScore() + ","
                    + fd.calculateNGPDEPScore());
            writer.newLine();
        }
    }
}
