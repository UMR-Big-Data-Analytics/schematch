package de.uni_marburg.schematch.utils;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Database;
import de.uni_marburg.schematch.data.Dataset;
import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.data.metadata.dependency.FunctionalDependency;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Exports gpdep for *every* ordered unary column pair, not only for discovered FDs.
 *
 * Everything so far has treated dependencies as a discovered, thresholded set:
 * a profiler proposes FDs and gpdep scores them. That makes the structural
 * signal depend on a discovery step and a cut-off. Computing gpdep for all
 * ordered pairs A -> B inside one table removes both, and yields a dense n x n
 * matrix describing how strongly each column determines each other column,
 * whether or not that ever qualified as an FD.
 *
 * Note that gpdep needs values observed together in the same tuples, so this is
 * necessarily *within* one side of a scenario. A cross-schema pair has no shared
 * tuples and no gpdep. What crosses the schema boundary is therefore not the
 * score itself but the profile it gives each column.
 *
 * One matrix per scenario and side, laid out like every other matrix the
 * harness reads: a header of column names, then one row per determinant.
 *
 * <pre>java -cp ... de.uni_marburg.schematch.utils.UnaryGpdepExporter out_dir</pre>
 */
public class UnaryGpdepExporter {
    private static final Logger log = LogManager.getLogger(UnaryGpdepExporter.class);

    public static void main(String[] args) throws IOException {
        Path outputDir = Paths.get(args.length > 0 ? args[0] : "unary_gpdep");
        Configuration config = Configuration.getInstance();

        for (Configuration.DatasetConfiguration datasetConfiguration : config.getDatasetConfigurations()) {
            Dataset dataset = new Dataset(datasetConfiguration);
            log.info("Unary gpdep for dataset {} ({} scenarios)",
                    dataset.getName(), dataset.getScenarioNames().size());

            for (String scenarioName : dataset.getScenarioNames()) {
                Scenario scenario = new Scenario(dataset.getPath() + File.separator + scenarioName);
                write(outputDir, dataset.getName(), scenarioName, "source", scenario.getSourceDatabase());
                write(outputDir, dataset.getName(), scenarioName, "target", scenario.getTargetDatabase());
            }
        }
        log.info("Wrote unary gpdep matrices to {}", outputDir.toAbsolutePath());
    }

    private static void write(Path outputDir, String dataset, String scenario,
                              String side, Database database) throws IOException {

        // Columns in the database's own order, which is the order every other
        // matrix and the ground truth already use.
        List<Table> tables = database.getTables();
        int total = database.getNumColumns();
        String[] names = new String[total];
        Column[] columns = new Column[total];
        for (Table table : tables) {
            List<Column> tableColumns = table.getColumns();
            for (int i = 0; i < tableColumns.size(); i++) {
                int index = table.getOffset() + i;
                columns[index] = tableColumns.get(i);
                names[index] = table.getName() + "." + tableColumns.get(i).getLabel();
            }
        }

        Path path = outputDir.resolve(dataset).resolve(scenario).resolve("gpdep_" + side + ".csv");
        Files.createDirectories(path.getParent());

        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            writer.write("Source\\Target");
            for (String name : names) {
                writer.write("," + name);
            }
            writer.newLine();

            for (int i = 0; i < total; i++) {
                StringBuilder line = new StringBuilder(names[i]);
                for (int j = 0; j < total; j++) {
                    double score = 0.0;
                    // gpdep is only defined for columns sharing tuples, so pairs
                    // spanning two tables of the same side stay at zero, as does
                    // the diagonal, where a column trivially determines itself.
                    if (i != j && columns[i].getTable() == columns[j].getTable()) {
                        score = new FunctionalDependency(List.of(columns[i]), columns[j])
                                .calculateGPDEPScore();
                    }
                    line.append(',').append(score);
                }
                writer.write(line.toString());
                writer.newLine();
            }
        }
    }
}
