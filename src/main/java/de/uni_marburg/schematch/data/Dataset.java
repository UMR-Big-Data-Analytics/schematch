package de.uni_marburg.schematch.data;

import de.uni_marburg.schematch.utils.Configuration;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.util.Arrays;

@Data
@NoArgsConstructor
public class Dataset {
    private String name;
    private String path;
    private List<String> scenarioNames;

    public Dataset(Configuration.DatasetConfiguration datasetConfiguration) {
        this.name = datasetConfiguration.getName();
        this.path = datasetConfiguration.getPath();
        this.scenarioNames = new ArrayList<>();

        File dir = new File(this.path);

        File[] children = dir.listFiles();
        if (children == null) throw new IllegalArgumentException("Cannot read dataset directory: " + this.path);
        Arrays.sort(children);
        Configuration config = Configuration.getInstance();
        for (File subdir : children) {
            boolean source = subdir.isDirectory() && Files.isDirectory(subdir.toPath().resolve(config.getDefaultSourceDatabaseDir()));
            boolean target = subdir.isDirectory() && Files.isDirectory(subdir.toPath().resolve(config.getDefaultTargetDatabaseDir()));
            if (source != target) throw new IllegalArgumentException("Incomplete source/target scenario: " + subdir);
            if (source && target) {
                this.scenarioNames.add(subdir.getName());
            }
        }
        if (scenarioNames.isEmpty()) throw new IllegalArgumentException("No source/target scenarios in dataset: " + this.path
                + "; configure a dataset directory, not a corpus root or raw benchmark release");
    }

    public String getStats() {
        StringBuilder sb = new StringBuilder();
        List<String> scenarioNames = this.getScenarioNames();
        sb.append("#Scenes: ").append(scenarioNames.size()).append("; "); //#Scenes

        int[] sourceTableRange = {Integer.MAX_VALUE, Integer.MIN_VALUE};
        int[] targetTableRange = {Integer.MAX_VALUE, Integer.MIN_VALUE};
        int[] fdRange = {Integer.MAX_VALUE, Integer.MIN_VALUE};   //combined source and target FDs
        int[] uccRange = {Integer.MAX_VALUE, Integer.MIN_VALUE};  //combined source and target UCCs
        int[] indRange = {Integer.MAX_VALUE, Integer.MIN_VALUE};  //combined source and target INDs

        // Helper lambda to update min/max
        java.util.function.BiConsumer<int[], Integer> updateRange = (range, value) -> {
            if (value < range[0]) {
                range[0] = value;
            }
            if (value > range[1]) {
                range[1] = value;
            }
        };

        for (String scenarioName : scenarioNames) {
            Scenario scenario = new Scenario(this.getPath() + File.separator + scenarioName);

            int countSourceTables = scenario.getSourceDatabase().getTables().size();
            int countTargetTables = scenario.getTargetDatabase().getTables().size();

            int countSourceFDs = scenario.getSourceDatabase().getMetadata().getFds().size();
            int countTargetFDs = scenario.getTargetDatabase().getMetadata().getFds().size();

            int countSourceUCCs = scenario.getSourceDatabase().getMetadata().getUccs().size();
            int countTargetUCCs = scenario.getTargetDatabase().getMetadata().getUccs().size();

            int countSourceINDs = scenario.getSourceDatabase().getMetadata().getInds().size();
            int countTargetINDs = scenario.getTargetDatabase().getMetadata().getInds().size();

            // Update min/max for each category
            updateRange.accept(sourceTableRange, countSourceTables);
            updateRange.accept(targetTableRange, countTargetTables);

            updateRange.accept(fdRange, countSourceFDs);
            updateRange.accept(fdRange, countTargetFDs);

            updateRange.accept(uccRange, countSourceUCCs);
            updateRange.accept(uccRange, countTargetUCCs);

            updateRange.accept(indRange, countSourceINDs);
            updateRange.accept(indRange, countTargetINDs);
        }

        // Append results
        sb.append("#Source Tables: ").append(sourceTableRange[0]).append(" - ").append(sourceTableRange[1]).append("; ");
        sb.append("#Target Tables: ").append(targetTableRange[0]).append(" - ").append(targetTableRange[1]).append("; ");
        sb.append("#FDs: ").append(fdRange[0]).append(" - ").append(fdRange[1]).append("; ");
        sb.append("#UCCs: ").append(uccRange[0]).append(" - ").append(uccRange[1]).append("; ");
        sb.append("#INDs: ").append(indRange[0]).append(" - ").append(indRange[1]);

        return sb.toString();
    }
}
