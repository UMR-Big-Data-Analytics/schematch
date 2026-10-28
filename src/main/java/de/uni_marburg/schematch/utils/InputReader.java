package de.uni_marburg.schematch.utils;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Database;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.data.metadata.DatabaseMetadata;
import de.uni_marburg.schematch.data.metadata.ScenarioMetadata;
import de.uni_marburg.schematch.data.metadata.dependency.FunctionalDependency;
import de.uni_marburg.schematch.data.metadata.dependency.InclusionDependency;
import de.uni_marburg.schematch.data.metadata.dependency.Metanome;
import de.uni_marburg.schematch.data.metadata.dependency.UniqueColumnCombination;
import de.uni_marburg.schematch.matching.Matcher;
import de.uni_marburg.schematch.matchtask.MatchTask;
import de.uni_marburg.schematch.matchtask.matchstep.MatchStep;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class InputReader {
    private static final Logger log = LogManager.getLogger(InputReader.class);

    public static List<Table> readDataDir(String inputPath, Database database) {
        return readDataDir(inputPath, Configuration.getInstance().getDefaultSeparator(), database);
    }

    public static List<Table> readDataDir(String inputPath, String separator, Database database) {
        List<Table> tables = new ArrayList<>();

        File dir = new File(inputPath);
        File[] listOfFiles = dir.listFiles(file -> file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".csv"));
        if (listOfFiles == null) throw new IllegalArgumentException("Cannot read table directory: " + inputPath);
        if (listOfFiles.length == 0) throw new IllegalArgumentException("No CSV tables in: " + inputPath);
        Arrays.sort(listOfFiles);

        for (File file : listOfFiles) {
            if (file.isFile()) {
                Table table = readDataFile(file.getAbsolutePath(), separator, database);
                tables.add(table);
            }
        }
        return tables;
    }

    public static Table readDataFile(String inputPath, Database database) {
        return readDataFile(inputPath, Configuration.getInstance().getDefaultSeparator(), database);
    }

    public static Table readDataFile(String inputPath, String separator, Database database) {
        return CsvTables.read(Path.of(inputPath), separator, database);
    }

    public static List<String> fetchGroundTruthTablePairNames(String inputPath) {
        List<String> gtTablePairNames = new ArrayList<>();

        File dir = new File(inputPath);
        File[] listOfFiles = dir.listFiles();

        for (File file : listOfFiles) {
            if (file.isFile()) {
                String fileName = StringUtils.getFileName(file);
                gtTablePairNames.add(fileName);
            }
        }

        return gtTablePairNames;
    }

    public static int[][] readGroundTruthFile(String inputPath) {
        return readGroundTruthFile(inputPath, Configuration.getInstance().getDefaultTablePairSeparator(),
                Configuration.getInstance().getDefaultSeparator());
    }
    private static int[][] readGroundTruthFile(String inputPath, String tablePairSeparator, String csvSeparator) {
        Path path = Path.of(inputPath);
        if (!Files.isRegularFile(path)) return null;
        int[][] matrix = GroundTruthReader.readUnlabeled(path, csvSeparator);
        return ArrayUtils.sumOfMatrix(matrix) > 0 ? matrix : null;
    }

    public static DatabaseMetadata readDatabaseMetadata(Database database) {
        try {
            String inputPath = database.getPath();
            String folderName = StringUtils.getFolderName(inputPath);
            Path metadataFolderPath = Paths.get(new File(inputPath).getParent(), "metadata", folderName);
            Path indFilePath = metadataFolderPath.resolve("inds.txt"); //getIndFilePath

            Map<Column, Collection<InclusionDependency>> indMap = new HashMap<>();
            Map<Column, Collection<FunctionalDependency>> fdMap = new HashMap<>();
            Map<Column, Collection<UniqueColumnCombination>> uccMap = new HashMap<>();

            Collection<InclusionDependency> inds = readINDFile(indFilePath, database, database, indMap);
            //Added that INDs are calculated
//            if(inds.isEmpty()) {
//                inds = Metanome.executePartialIND(database.getTables());
//            }

            Collection<FunctionalDependency> fds = new ArrayList<>();
            Collection<UniqueColumnCombination> uccs = new ArrayList<>();

            for (Table table : database.getTables()) {
                Path fdFilePath = metadataFolderPath.resolve(table.getName()).resolve("FD_results.txt");
                Path uccFilePath = metadataFolderPath.resolve(table.getName()).resolve("UCC_results.txt");

                ManagedProfiles.requireFD(Path.of(inputPath).getParent(), folderName,
                        Path.of(table.getPath()), fdFilePath);
                Collection<FunctionalDependency> datasetFDs = readFDFile(fdFilePath, table, fdMap);
//                if(datasetFDs.isEmpty()) {
//                    // datasetFDs = Metanome.executeFD(List.of(table));
//                    datasetFDs = Metanome.executePartialFD(List.of(table));
//                }
//                for (FunctionalDependency fd : datasetFDs) {
//                    fd.setPdepTriple(MetadataUtils.getPdep(fd));
//                }
                Collection<UniqueColumnCombination> datasetUCCs = readUCCFile(uccFilePath, table, uccMap);
//                if(datasetUCCs.isEmpty()) {
//                    // datasetUCCs = Metanome.executeUCC(List.of(table));
//                    datasetUCCs = Metanome.executePartialUCC(List.of(table));
//                }
                fds.addAll(datasetFDs);
                uccs.addAll(datasetUCCs);
            }
            DatabaseMetadata metadata = new DatabaseMetadata(uccs, fds, inds);
            metadata.getIndMap().putAll(indMap);
            metadata.getFdMap().putAll(fdMap);
            metadata.getUccMap().putAll(uccMap);

            return metadata;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    public static ScenarioMetadata readScenarioMetadata(String inputPath, Database sourceDatabase, Database targetDatabase){
        try {
            Path metadataFolderPath = Paths.get(inputPath, "metadata");
            Path sourceFilePath = metadataFolderPath.resolve("source-to-target-inds.txt");
            Path targetFilePath = metadataFolderPath.resolve("target-to-source-inds.txt");

            Map<Column, Collection<InclusionDependency>> sourceContentMap = new HashMap<>();
            Map<Column, Collection<InclusionDependency>> targetContentMap = new HashMap<>();

            Collection<InclusionDependency> sourceContent = readINDFile(sourceFilePath, sourceDatabase, targetDatabase, sourceContentMap);
            Collection<InclusionDependency> targetContent = readINDFile(targetFilePath, targetDatabase, sourceDatabase, targetContentMap);

            ScenarioMetadata metadata = new ScenarioMetadata(sourceContent, targetContent);
            metadata.getSourceToTargetMap().putAll(sourceContentMap);
            metadata.getTargetToSourceMap().putAll(targetContentMap);
            return metadata;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    
    
    /**
     * Resolves a qualified name from a metadata file to a column of the table.
     *
     * Profilers qualify columns inconsistently: some emit "table.column",
     * others "table.csv.column". Rather than assume a fixed number of
     * segments, every dot-separated suffix is tested against the table's
     * labels, longest first, so a column whose own name contains a dot still
     * wins over a shorter accidental match.
     *
     * @throws IllegalArgumentException naming the offending value, instead of
     *         letting an unresolved name surface as an index out of bounds.
     */
    /**
     * Removes a trailing profiler measurement such as " (pdep 0.32, 0.0)".
     *
     * The annotation contains a comma, so it has to go before a right-hand
     * side is split on commas into individual columns.
     */
    public static String stripAnnotation(String value) {
        // Parentheses also occur in real column labels (OpenData/GDC).
        // Only the reserved trailing profiler annotation is metadata.
        int annotation = value.lastIndexOf(" (pdep ");
        return (annotation >= 0 ? value.substring(0, annotation) : value).trim();
    }

    public static Column resolveColumn(String qualifiedName, Table table) {
        String name = stripAnnotation(qualifiedName);
        List<String> labels = table.getLabels();

        int index = labels.indexOf(name);
        if (index >= 0) {
            return table.getColumn(index);
        }
        for (int i = name.indexOf('.'); i >= 0; i = name.indexOf('.', i + 1)) {
            index = labels.indexOf(name.substring(i + 1));
            if (index >= 0) {
                return table.getColumn(index);
            }
        }
        throw new IllegalArgumentException(
                "Cannot resolve column '" + qualifiedName + "' in table '"
                        + table.getName() + "'; known columns: " + labels);
    }

    public static Collection<FunctionalDependency> readFDFile(Path filePath, Table table, Map<Column, Collection<FunctionalDependency>> map) throws IOException{
        Set<FunctionalDependency> fds = new HashSet<>();
        if(!filePath.toFile().exists()) {
            filePath.toFile().getParentFile().mkdirs();
            filePath.toFile().createNewFile();
        }
        List<String> lines = Files.readAllLines(filePath);
        for (String line : lines) {
            if(line.isEmpty() || line.isBlank())
                continue;
            String[] split = line.split(" --> ");
            if(split[0].equalsIgnoreCase("[]")) {
                for (String right : stripAnnotation(split[1]).split(",")) {
                    Column rightC = resolveColumn(right, table);
                    for (Column leftCol : table.getColumns()){
                        // Compare resolved columns: the right-hand side is a
                        // qualified name, so comparing it to a bare label
                        // never matched and admitted trivial FDs A -> A.
                        if(leftCol.equals(rightC))
                            continue;
                        List<Column> leftCC = List.of(leftCol);
                        extracted(map, leftCC, rightC, fds);
                    }
                }
            } else {
                Collection<Column> leftCC = (Collection<Column>) extractColumnsFromString(split[0], table);
                for (String right : stripAnnotation(split[1]).split(",")) {
                    Column rightC = resolveColumn(right, table);
                    extracted(map, leftCC, rightC, fds);
                }
            }
        }
        return fds;
    }

    private static void extracted(Map<Column, Collection<FunctionalDependency>> map, Collection<Column> leftCC, Column rightC, Set<FunctionalDependency> fds) {
        FunctionalDependency fd = new FunctionalDependency(leftCC, rightC);
        fds.add(fd);
        map.computeIfAbsent(rightC, k -> new ArrayList<>()).add(fd);
        for (Column left : leftCC) {
            map.computeIfAbsent(left, k -> new ArrayList<>()).add(fd);
        }
    }

    public static Collection<UniqueColumnCombination> readUCCFile(Path filePath, Table table, Map<Column, Collection<UniqueColumnCombination>> map) throws IOException{
        Set<UniqueColumnCombination> uccs = new HashSet<>();
        if(!filePath.toFile().exists()) {
            filePath.toFile().getParentFile().mkdirs();
            filePath.toFile().createNewFile();
        }
        List<String> lines = Files.readAllLines(filePath);
        for (String line : lines) {
            if(line.isEmpty() || line.isBlank())
                continue;
            if(line.equalsIgnoreCase("[]"))
                continue;
            Collection<Column> columns = (Collection<Column>) extractColumnsFromString(line, table);
            UniqueColumnCombination ucc = new UniqueColumnCombination(columns);
            uccs.add(ucc);
            for (Column left: columns){
                map.computeIfAbsent(left, k -> new ArrayList<>()).add(ucc);
            }
        }
        return uccs;
    }

    public static Collection<InclusionDependency> readINDFile(Path filePath, Database leftDatabase, Database rightDatabase, Map<Column, Collection<InclusionDependency>> map) throws IOException {
        return readINDFile(filePath, leftDatabase.getTables(), rightDatabase.getTables(), map);
    }

    public static Table getTableByName(String tableName, List<Table> tables) {
        for (Table table : tables) {
            if (table.getName().equals(tableName)) {
                return table;
            }
        }
        return null;
    }

    public static Collection<InclusionDependency> readINDFile(Path filePath, List<Table> leftDatabase, List<Table> rightDatabase, Map<Column, Collection<InclusionDependency>> map) throws IOException{
        Set<InclusionDependency> inds = new HashSet<>();
        if(!filePath.toFile().exists()) {
            filePath.toFile().getParentFile().mkdirs();
            filePath.toFile().createNewFile();
        }

        List<String> lines = Files.readAllLines(filePath);
        for (String line : lines) {
            if(line.isEmpty() || line.isBlank())
                continue;
            String[] split = line.split(" (?:\\[=|-->) ");

            Collection<String[]> supersetCCString = (Collection<String[]>) extractColumnsFromString(split[0], null);

            Collection<Column> supersetCC = extractINDs(filePath, leftDatabase, supersetCCString);

            //TODO: Problem is here?
            for(String right : split[1].split("], ")){
                right = right.trim();
                if(right.charAt(right.length()-1) != ']')
                    right = right + "]";
                Collection<String[]> subsetCCString = (Collection<String[]>) extractColumnsFromString(right, null);
                Collection<Column> subsetCC = extractINDs(filePath, rightDatabase, subsetCCString);
                InclusionDependency ind = new InclusionDependency(subsetCC, supersetCC);
                inds.add(ind);
                for (Column left: subsetCC){
                    map.computeIfAbsent(left, k -> new ArrayList<>()).add(ind);
                }
                for (Column left: supersetCC){
                    map.computeIfAbsent(left, k -> new ArrayList<>()).add(ind);
                }
            }
        }
        return inds;
    }

    private static Collection<Column> extractINDs(Path filePath, List<Table> rightDatabase, Collection<String[]> subsetCCString) {
        Collection<Column> setCC = new ArrayList<>();
        for(String[] tableColumnPair: subsetCCString){
            Table table = getTableByName(tableColumnPair[0], rightDatabase);
            if(table == null)
                throw new RuntimeException("While reading in metadata from " + filePath + " an error occurred, table " + tableColumnPair[0] + " cannot be found!");
            setCC.add(table.getColumn(table.getLabels().indexOf(tableColumnPair[1])));
        }
        return setCC;
    }

    private static Collection<?> extractColumnsFromString(String input, Table table) {
        List<Object> columnList = new ArrayList<>();
        while (input.contains("]")) {
            int start = 0;
            int end = -1;

            if (input.contains("[")) {
                start = input.indexOf("[");
            }

            if (input.contains(",")) {
                end = input.indexOf(",");
            } else {
                end = input.indexOf("]");
            }

            String output = input.substring(start + 1, end);
            input = input.substring(end + 1);

            if (table == null) {
                String qualified = output.trim();
                columnList.add(qualified.contains(".csv.")
                        ? qualified.split("\\.csv\\.", 2) : qualified.split("\\.", 2));
            } else {
                columnList.add(resolveColumn(output, table));
            }
        }
        return columnList;
    }

    public static float[][] readCache(MatchTask matchTask, MatchStep matchStep, Matcher matcher, String separator) {
        Path file = ResultsUtils.getCachePathForMatchStepInScenario(matchTask,matchStep)
                .resolve(OutputWriter.toSafeFileName(matcher.toString())+".csv");
        if (!Files.isRegularFile(file)) return null;
        float[][] matrix = matchTask.getEmptySimMatrix();
        CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(separator).setIgnoreEmptyLines(false).build();
        try (Reader reader = CsvTables.reader(file); CSVParser parser = new CSVParser(reader,format)) {
            int i = 0;
            for (CSVRecord record : parser) {
                if (i >= matrix.length || record.size() != matrix[i].length)
                    throw new IllegalStateException("Cache dimensions differ from scenario: " + file);
                for (int j = 0; j < matrix[i].length; j++) {
                    float value = Float.parseFloat(record.get(j));
                    if (!Float.isFinite(value)) throw new IllegalStateException("Non-finite cached score: " + file);
                    matrix[i][j] = value;
                }
                i++;
            }
            if (i != matrix.length) throw new IllegalStateException("Truncated matrix cache: " + file);
            matchTask.incrementCacheRead();
            return matrix;
        } catch (IOException | UncheckedIOException | NumberFormatException e) {
            throw new IllegalStateException("Cannot read similarity cache " + file,e);
        }
    }

    public static float[][] readCache(MatchTask matchTask, MatchStep matchStep, Matcher matcher) {
        return readCache(matchTask, matchStep, matcher, Configuration.getInstance().getDefaultSeparator());
    }
}
