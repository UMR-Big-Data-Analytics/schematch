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
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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

    public static void writeSimMatrix(Path path, MatchTask task, String matcherInfo, float[][] scores) {
        Path file = path.resolve(toSafeFileName(matcherInfo) + ".csv");
        Configuration config = Configuration.getInstance();
        boolean cache = path.toAbsolutePath().normalize().startsWith(Path.of(config.getCacheDir()).toAbsolutePath().normalize());
        boolean verbose = config.isSaveOutputVerbose() && !cache;
        List<String> source = GroundTruthReader.columns(task.getScenario().getSourceDatabase()).stream().map(GroundTruthReader.ColumnRef::fullName).toList();
        List<String> target = GroundTruthReader.columns(task.getScenario().getTargetDatabase()).stream().map(GroundTruthReader.ColumnRef::fullName).toList();
        if (scores.length != source.size()) throw new IllegalArgumentException("Invalid source dimensions for output");
        for (float[] row : scores) if (row.length != target.size()) throw new IllegalArgumentException("Invalid target dimensions for output");
        print(file,scores,0,0,source,target,verbose);
        if (config.isSaveOutputPerTablePair()) {
            for (TablePair pair : task.getTablePairs()) {
                Path block = path.resolve("tablepairs").resolve(toSafeFileName(matcherInfo)).resolve(pair.toString()+".csv");
                print(block,scores,pair.getSourceTable().getOffset(),pair.getTargetTable().getOffset(),
                        pair.getSourceTable().getLabels(),pair.getTargetTable().getLabels(),verbose);
            }
        }
    }

    private static void print(Path file, float[][] scores, int sourceOffset, int targetOffset,
                              List<String> source, List<String> target, boolean verbose) {
        CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(Configuration.getInstance().getDefaultSeparator())
                .setRecordSeparator(System.lineSeparator()).build();
        try {
            Files.createDirectories(file.getParent());
            try (var writer = Files.newBufferedWriter(file,StandardCharsets.UTF_8); CSVPrinter printer = new CSVPrinter(writer,format)) {
                if (verbose) {
                    List<Object> header = new ArrayList<>(); header.add("Source\\Target"); header.addAll(target);
                    printer.printRecord(header);
                }
                for (int i = 0; i < source.size(); i++) {
                    List<Object> row = new ArrayList<>();
                    if (verbose) row.add(source.get(i));
                    for (int j = 0; j < target.size(); j++) row.add(scores[sourceOffset+i][targetOffset+j]);
                    printer.printRecord(row);
                }
            }
        } catch (IOException e) { throw new java.io.UncheckedIOException("Cannot write matrix " + file,e); }
    }
}
