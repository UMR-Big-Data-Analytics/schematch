package de.uni_marburg.schematch.utils;

import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matching.Matcher;
import de.uni_marburg.schematch.matchtask.MatchTask;
import de.uni_marburg.schematch.matchtask.matchstep.MatchStep;
import de.uni_marburg.schematch.matchtask.matchstep.MatchingStep;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class OutputWriter {
    private final static Logger log = LogManager.getLogger(OutputWriter.class);

    // Matcher names embed every parameter, which for SimilarityFlooding exceeds
    // the 255-character file name limit of NTFS and ext4 alike. Names longer
    // than this are truncated and disambiguated with a hash of the full name.
    private final static int MAX_FILE_NAME_LENGTH = 240;

    /**
     * Shortens a matcher name to a length the file system accepts, keeping it
     * unique by appending a hash of the original. Names that already fit are
     * returned unchanged, so existing output paths do not move.
     *
     * Both ends of the name are preserved: matcher names share a long common
     * prefix of fixed parameters, so the parameters that actually distinguish
     * one configuration from another sit at the end. Dropping the middle keeps
     * the output self-describing rather than a wall of identical prefixes.
     */
    public static String toSafeFileName(String name) {
        // Matcher names embed their parameter values, and a value can be a
        // path: initialMatrixDir is one. Separators and the Windows drive
        // colon then make the file name an invalid path, which surfaces as an
        // InvalidPathException from deep inside the writer rather than as
        // anything a caller could interpret. Replace the characters no file
        // name may contain before anything else looks at the length.
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.length() <= MAX_FILE_NAME_LENGTH) {
            return name;
        }
        String hash = String.format("_%08x_", name.hashCode());
        int budget = MAX_FILE_NAME_LENGTH - hash.length();
        int head = budget / 5;
        int tail = budget - head;
        return name.substring(0, head) + hash + name.substring(name.length() - tail);
    }

    public static void writeSimMatrix(Path path, MatchTask matchTask, String matcherInfo, float[][] simMatrix) {
        Path pathToFile = path.resolve(toSafeFileName(matcherInfo) + ".csv");
        boolean isCacheFile = path.toString().startsWith(Configuration.getInstance().getCacheDir());

        try {
            Files.createDirectories(pathToFile.getParent());
            BufferedWriter writer = new BufferedWriter(new FileWriter(pathToFile.toString()));
            if (Configuration.getInstance().isSaveOutputVerbose() &&
                    !isCacheFile) { // always cache plain sim matrices
                StringBuilder line = new StringBuilder();
                line.append("Source\\Target");
                for (Table table : matchTask.getScenario().getTargetDatabase().getTables()) {
                    for (String targetLabel : table.getLabels()) {
                        line.append(Configuration.getInstance().getDefaultSeparator());
                        line.append(table.getName() + "." + targetLabel);
                    }
                }
                writer.write(line.toString());
                writer.newLine();
            }
            for (int i = 0; i < simMatrix.length; i++) {
                float[] scoreList = simMatrix[i];
                StringBuilder line = new StringBuilder();
                if (Configuration.getInstance().isSaveOutputVerbose() &&
                        !isCacheFile) { // always cache plain sim matrices
                    line.append(matchTask.getScenario().getSourceDatabase().getFullColumnNameByIndex(i));
                    line.append(Configuration.getInstance().getDefaultSeparator());
                }
                for (float score : scoreList) {
                    line.append(score).append(Configuration.getInstance().getDefaultSeparator());
                }
                writer.write(line.substring(0, line.length() - 1));
                writer.newLine();
            }
            writer.close();
        } catch (IOException e) {
            log.error(e.getMessage());
        }

        if (Configuration.getInstance().isSaveOutputPerTablePair()) {
            writeTablePairSimMatrices(path, matchTask, matcherInfo, simMatrix);
        }
    }

    private static void writeTablePairSimMatrices(Path path, MatchTask matchTask, String matcherInfo, float[][] simMatrix) {
        Path pathToTablePairs = path.resolve("tablepairs");
        Path pathToMatcher = pathToTablePairs.resolve(toSafeFileName(matcherInfo));

        for (TablePair tablePair : matchTask.getTablePairs()) {
            Path pathToTablePair = pathToMatcher.resolve(tablePair.toString() + ".csv");
            try {
                Files.createDirectories(pathToTablePair.getParent());
                BufferedWriter tpWriter = new BufferedWriter(new FileWriter(pathToTablePair.toString()));
                Table sourceTable = tablePair.getSourceTable();
                Table targetTable = tablePair.getTargetTable();
                int sourceOffset = sourceTable.getOffset();
                int targetOffset = targetTable.getOffset();
                int numSourceColumns = sourceTable.getNumColumns();
                int numTargetColumns = targetTable.getNumColumns();
                if (Configuration.getInstance().isSaveOutputVerbose()) {
                    StringBuilder line = new StringBuilder();
                    line.append("Source\\Target");
                    for (String targetLabel : targetTable.getLabels()) {
                        line.append(Configuration.getInstance().getDefaultSeparator());
                        line.append(targetLabel);
                    }
                    tpWriter.write(line.toString());
                    tpWriter.newLine();
                }
                for (int i = sourceOffset; i < (sourceOffset+numSourceColumns); i++) {
                    StringBuilder line = new StringBuilder();
                    if (Configuration.getInstance().isSaveOutputVerbose()) {
                        line.append(sourceTable.getColumn(i-sourceOffset).getLabel());
                        line.append(Configuration.getInstance().getDefaultSeparator());
                    }
                    for (int j = targetOffset; j < (targetOffset+numTargetColumns); j++) {
                        line.append(simMatrix[i][j]).append(Configuration.getInstance().getDefaultSeparator());
                    }
                    tpWriter.write(line.substring(0, line.length() - 1));
                    tpWriter.newLine();
                }
                tpWriter.close();
            } catch (IOException e) {
                log.error(e.getMessage());
            }
        }
    }
}
