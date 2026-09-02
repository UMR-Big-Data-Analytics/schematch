package de.uni_marburg.schematch.matching.similarityFlooding;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Database;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matching.Matcher;
import de.uni_marburg.schematch.matchtask.MatchTask;
import de.uni_marburg.schematch.matchtask.matchstep.MatchStep;
import de.uni_marburg.schematch.matchtask.matchstep.MatchingStep;
import de.uni_marburg.schematch.similarity.string.Levenshtein;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jgrapht.Graph;

import java.lang.reflect.Field;
import java.util.*;

import static de.uni_marburg.schematch.matching.similarityFlooding.SchemaGraphBuilder.createConnectivityGraph;
import static de.uni_marburg.schematch.matching.similarityFlooding.SchemaGraphBuilder.inducePropagationGraph;
import static de.uni_marburg.schematch.matching.similarityFlooding.SimilarityFloodingUtils.*;

@NoArgsConstructor
@AllArgsConstructor
@Setter
@Getter
public class SimilarityFlooding extends Matcher {

    private static final Logger log = LogManager.getLogger(SimilarityFlooding.class);

    //Fixed parameters
    private static final double LABEL_SCORE_WEIGHT = 0.5;
    public static final double SELECT_THRESHOLD_WEIGHT = 0.95;
    private static final double PRECISION_OF_CONVERGENCE = 0.0001;
    private static final int MAX_ITERATIONS = 200;

    //Free parameters
    private String propCoeffPolicy;
    private String fixpoint;
    private String uccFilterThreshold;
    private String indFilterThreshold;
    private String fdFilterThreshold;
    private String constraintWeighting;
    private String graphEncoding = "COMPLETE";
    private String dependencyTopK = "";
    private String fdAllowListFile = "";
    private String initialMatrix = "PREVIOUS";
    private String initialMatrixDir = "";

    private static Map<NodePair, Double> similarityFlooding
            (Graph<NodePair, CoefficientEdge> propagationGraph, Map<NodePair, Double> initialMapping, FixpointFormula formula) {

        //Termination conditions
        boolean convergence = false;
        int iterationCount = 0;

        // Index the map pairs once. The iteration below is the hot loop of the
        // whole matcher, and resolving NodePair keys through hash maps on every
        // neighbour dominated its runtime; with an index the state is plain
        // double arrays and the neighbourhood is a CSR adjacency.
        List<NodePair> nodes = new ArrayList<>(propagationGraph.vertexSet());
        int nodeCount = nodes.size();
        Map<NodePair, Integer> indexOf = new HashMap<>(Math.max(16, nodeCount * 2));
        for (int i = 0; i < nodeCount; i++) {
            indexOf.put(nodes.get(i), i);
        }

        double[] sigma_0 = new double[nodeCount];
        boolean[] isDependencyPair = new boolean[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            NodePair node = nodes.get(i);
            sigma_0[i] = initialMapping.getOrDefault(node, 0.0);
            isDependencyPair[i] = node.getFirstNode().getNodeType().equals(NodeType.CONSTRAINT)
                    && node.getSecondNode().getNodeType().equals(NodeType.CONSTRAINT);
        }

        // Compressed sparse row over incoming edges: rowPtr[i]..rowPtr[i+1]
        // are the edges flowing into node i.
        int[] rowPtr = new int[nodeCount + 1];
        for (int i = 0; i < nodeCount; i++) {
            rowPtr[i + 1] = rowPtr[i] + propagationGraph.incomingEdgesOf(nodes.get(i)).size();
        }
        int[] src = new int[rowPtr[nodeCount]];
        double[] coeff = new double[rowPtr[nodeCount]];
        for (int i = 0; i < nodeCount; i++) {
            int k = rowPtr[i];
            for (CoefficientEdge edge : propagationGraph.incomingEdgesOf(nodes.get(i))) {
                src[k] = indexOf.get(propagationGraph.getEdgeSource(edge));
                coeff[k] = edge.getCoefficient();
                k++;
            }
        }

        double[] sigma_i = sigma_0.clone();
        double[] sigma_i_plus_1 = new double[nodeCount];

        while (!convergence && iterationCount <= MAX_ITERATIONS) {

            double maxValueCurrentIteration = Double.MIN_VALUE;

            for (int node = 0; node < nodeCount; node++) {
                double newValue = formula.evaluate(node, sigma_0, sigma_i, rowPtr, src, coeff, isDependencyPair);

                //MaxWert der aktuellen Iteration speichern, damit später normalisieren möglich
                if (newValue > maxValueCurrentIteration) {
                    maxValueCurrentIteration = newValue;
                }

                sigma_i_plus_1[node] = newValue;
            }

            //Normalisieren für die aktuelle Iteration
            for (int node = 0; node < nodeCount; node++) {
                sigma_i_plus_1[node] = sigma_i_plus_1[node] / maxValueCurrentIteration;
            }

            //Prüfen ob Residuum(sigma_i, sigma_i+1) konvergiert
            convergence = hasConverged(sigma_i, sigma_i_plus_1, PRECISION_OF_CONVERGENCE);
            System.arraycopy(sigma_i_plus_1, 0, sigma_i, 0, nodeCount);
            iterationCount++;
        }

        log.info("Ran {} Iterations for {} Nodes", iterationCount - 1, initialMapping.size());

        Map<NodePair, Double> result = new HashMap<>(Math.max(16, nodeCount * 2));
        for (int i = 0; i < nodeCount; i++) {
            result.put(nodes.get(i), sigma_i_plus_1[i]);
        }
        return result;
    }

    public static Map<String, Collection<String>> getPossibleValues() {
        Map<String, Collection<String>> possibleValues = new HashMap<>();
        for (Param param : Param.values()) {
            possibleValues.put(param.key, param.possibleValues);
        }
        return possibleValues;
    }

    @Override
    public float[][] match(MatchTask matchTask, MatchingStep matchStep) {

        PropagationCoefficientPolicy policy;
        FixpointFormula formula;

        policy = switch (propCoeffPolicy) {
            case "INV_AVG" -> PropagationCoefficientPolicy.INVERSE_AVERAGE;
            case "INV_PROD" -> PropagationCoefficientPolicy.INVERSE_PRODUCT;
            case "CONSTANT_ONE" -> PropagationCoefficientPolicy.CONSTANT_ONE;
            default -> throw new RuntimeException("No such propagation coefficient policy: " + propCoeffPolicy);
        };

        formula = switch (fixpoint) {
            case "BASIC" -> FixpointFormula.BASIC;
            case "A" -> FixpointFormula.FORMULA_A;
            case "B" -> FixpointFormula.FORMULA_B;
            case "C" -> FixpointFormula.FORMULA_C;
            case "BASIC_Lambda" -> FixpointFormula.BASIC_Lambda;
            case "A_Lambda" -> FixpointFormula.FORMULA_A_Lambda;
            case "B_Lambda" -> FixpointFormula.FORMULA_B_Lambda;
            case "C_Lambda" -> FixpointFormula.FORMULA_C_Lambda;
            default -> throw new RuntimeException("No such fixpoint formula: " + fixpoint);
        };

        float[][] simMatrix = matchTask.getEmptySimMatrix();

        Database sourceDb = matchTask.getScenario().getSourceDatabase();
        Database targetDb = matchTask.getScenario().getTargetDatabase();

//        if (this.indFilterThreshold == null) {
//            log.warn("No IND filter threshold specified. Using default value: {}", IND_FILTER_THRESHOLD_DEFAULT);
//            this.indFilterThreshold = IND_FILTER_THRESHOLD_DEFAULT;
//        }
//
//        if (this.fdFilterThreshold == null) {
//            log.warn("No FD filter threshold specified. Using default value: {}", FD_FILTER_THRESHOLD_DEFAULT);
//            this.fdFilterThreshold = FD_FILTER_THRESHOLD_DEFAULT;
//        }
//
//        if (this.uccFilterThreshold == null) {
//            log.warn("No UCC filter threshold specified. Using default value: {}", UCC_FILTER_THRESHOLD_DEFAULT);
//            this.uccFilterThreshold = UCC_FILTER_THRESHOLD_DEFAULT;
//        }

        List<Matcher> matcher = matchTask.getFirstLineMatchers();
        Matcher firstLineMatcher = matcher.get(0);
        float [][] prevMatrix = switch (initialMatrix) {
            case "PREVIOUS" -> matchTask.getSimMatrixFromPreviousMatchStep(matchStep, firstLineMatcher);
            case "RAW_FIRST_LINE" -> matchTask.getFirstLineSimMatrix(firstLineMatcher);
            case "FILE" -> readSeedMatrix(matchTask, sourceDb, targetDb);
            default -> throw new IllegalArgumentException("Unknown initial matrix: " + initialMatrix);
        };

        SchemaGraphBuilder schemaGraphBuilder = new SchemaGraphBuilder(this.uccFilterThreshold, this.indFilterThreshold, this.fdFilterThreshold, this.constraintWeighting, this.graphEncoding, this.dependencyTopK, this.fdAllowListFile);

        Graph<Node, LabelEdge> sourceGraph = schemaGraphBuilder.transformIntoGraphRepresentationSchema(sourceDb);
        Graph<Node, LabelEdge> targetGraph = schemaGraphBuilder.transformIntoGraphRepresentationSchema(targetDb);

        //Combine both Graphs into a connectivity-graph
        Graph<NodePair, LabelEdge> connectivityGraph = createConnectivityGraph(sourceGraph, targetGraph);

        //Transform the connectivity-graph into the propagation-graph on which the algorithm executes
        Graph<NodePair, CoefficientEdge> propagationGraph = inducePropagationGraph(connectivityGraph, sourceGraph, targetGraph, policy);

        //Calculate the initial mapping (similarity) values
        Map<NodePair, Double> initialMapping = buildInitialMappingFromMatrix(prevMatrix,sourceDb, targetDb);

        //Run the similarity-flooding algorithm
        Map<NodePair, Double> floodingResults = similarityFlooding(propagationGraph, initialMapping, formula);

        //Apply constraints/filters to the result
        Map<NodePair, Double> filteredFloodingResults = filterMapping(floodingResults);

        for (Table sourceTable : matchTask.getScenario().getSourceDatabase().getTables()) {
            for (Table targetTable : matchTask.getScenario().getTargetDatabase().getTables()) {
                populateSimMatrix(simMatrix, filteredFloodingResults, sourceTable, targetTable);
            }
        }

        return simMatrix;
    }

    private Map<NodePair, Double> calculateInitialMapping(Graph<NodePair, CoefficientEdge> propagationGraph) {
        //Method to calculate initial similarities between NodePairs
        //Currently not used, as SimilarityFlooding Algorithm is used as second-line matcher

        Map<NodePair, Double> initialMapping = new HashMap<>();

        for (NodePair mappingPair : propagationGraph.vertexSet()) {
            Node node1 = mappingPair.getFirstNode();
            Node node2 = mappingPair.getSecondNode();
            Levenshtein l = new Levenshtein();
            double similarity;

            //All Pairs with ID Nodes get assigned a initial similarity of 0.0
            if (node1.getValue().startsWith("FD") && node2.getValue().startsWith("FD")) {
                similarity = 0.0;
            } else if (node1.getValue().startsWith("UCC") && node2.getValue().startsWith("UCC")) {
                similarity = 0.0;
            } else if (node1.getValue().startsWith("IND") && node2.getValue().startsWith("IND")) {
                similarity = 0.0;
            } else if (node1.isIDNode() || node2.isIDNode()) {
                similarity = 0.0;

            //Otherwise if value Nodes assign combination of LabelScore and ValueScore (if applicable)
            } else {
                Column column1 = node1.getRepresentedColumn();
                Column column2 = node2.getRepresentedColumn();
                double labelSimilarity = l.compare(node1.getValue(), node2.getValue());

                if (column1 == null || column2 == null) {
                    similarity = labelSimilarity;
                } else {
                    //First Line Matcher
                    double labelWeight = LABEL_SCORE_WEIGHT;
                    double valueSimilarity = getValueSimilarityBetweenColumns(column1, column2);
                    similarity = labelWeight * labelSimilarity + (1 - labelWeight) * valueSimilarity;
                }
            }

            initialMapping.put(mappingPair, similarity);

        }
        return initialMapping;
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder(getClass().getSimpleName());
        result.append("(");

        Field[] fields = getClass().getDeclaredFields();

        for (int i = 1; i < fields.length; i++) {
            try {
                fields[i].setAccessible(true);
                result.append(fields[i].getName()).append("=").append(fields[i].get(this)).append(" &  ");
                fields[i].setAccessible(false);
            } catch (IllegalAccessException ignored) {
            } // Cannot happen, we have set the field to be accessible
        }
        String res = result.toString();
        if (getClass().getDeclaredFields().length > 0) {
            res = res.substring(0, res.length() - 4);
        }
        return res + ")";
    }

    public Map<String, String> getParameters() {
        Map<String, String> parameters = new HashMap<>();
        for (Param param : Param.values()) {
            try {
                Field field = this.getClass().getDeclaredField(param.key);
                field.setAccessible(true);
                parameters.put(param.key, (String) field.get(this));
            } catch (Exception ignored) {
            }
        }
        return parameters;
    }

    public void setParameters(Map<String, String> currentParams) {
        for (Param param : Param.values()) {
            try {
                Field field = this.getClass().getDeclaredField(param.key);
                field.setAccessible(true);
                field.set(this, currentParams.get(param.key));
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Seeds the flooding from a similarity matrix produced outside Schematch.
     *
     * Similarity Flooding does not score columns from scratch; it propagates
     * whatever a first-line matcher already produced. That makes the seed a
     * free choice, but the only seeds reachable through the configuration are
     * Schematch's own matchers, so a matrix from another system -- PRISMA, say
     * -- could not be used as a starting point without this.
     *
     * The file is expected at &lt;initialMatrixDir&gt;/&lt;dataset&gt;/&lt;scenario&gt;/seed.csv,
     * with a header row of "table.column" labels and a matching first column,
     * which is the format Schematch itself writes with saveOutputVerbose. The
     * columns are realigned by label rather than trusted in file order.
     *
     * Every failure here throws. A seed matrix that silently reads as zeros
     * would still produce a complete, plausible-looking run, and that class of
     * fault has already cost this project several days of misleading numbers.
     */
    private float[][] readSeedMatrix(MatchTask matchTask, Database sourceDb, Database targetDb) {
        if (this.initialMatrixDir == null || this.initialMatrixDir.isBlank()) {
            throw new IllegalArgumentException("initialMatrix=FILE needs initialMatrixDir");
        }
        java.nio.file.Path scenarioPath = java.nio.file.Paths.get(
                matchTask.getScenario().getPath());
        String scenario = scenarioPath.getFileName().toString();
        String dataset = scenarioPath.getParent().getFileName().toString();
        java.nio.file.Path seedPath = java.nio.file.Paths.get(this.initialMatrixDir)
                .resolve(dataset).resolve(scenario).resolve("seed.csv");

        List<String> lines;
        try {
            lines = java.nio.file.Files.readAllLines(seedPath);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read seed matrix " + seedPath, e);
        }
        if (lines.size() < 2) {
            throw new IllegalStateException("seed matrix has no rows: " + seedPath);
        }

        String[] header = lines.get(0).split(",", -1);
        Map<String, Integer> columnOf = new HashMap<>();
        for (int j = 1; j < header.length; j++) {
            columnOf.put(normaliseLabel(header[j]), j - 1);
        }
        Map<String, Integer> rowOf = new HashMap<>();
        List<String[]> body = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] cells = lines.get(i).split(",", -1);
            if (cells.length < 2) {
                continue;
            }
            rowOf.put(normaliseLabel(cells[0]), body.size());
            body.add(cells);
        }

        List<String> sourceLabels = labelsOf(sourceDb);
        List<String> targetLabels = labelsOf(targetDb);
        float[][] out = new float[sourceLabels.size()][targetLabels.size()];
        int resolved = 0;
        for (int i = 0; i < sourceLabels.size(); i++) {
            Integer r = rowOf.get(sourceLabels.get(i));
            if (r == null) {
                continue;
            }
            String[] cells = body.get(r);
            for (int j = 0; j < targetLabels.size(); j++) {
                Integer c = columnOf.get(targetLabels.get(j));
                if (c == null || c + 1 >= cells.length) {
                    continue;
                }
                try {
                    out[i][j] = parseCell(cells[c + 1]);
                    resolved++;
                } catch (NumberFormatException ignored) {
                    // a non-numeric cell stays zero
                }
            }
        }

        int expected = sourceLabels.size() * targetLabels.size();
        if (resolved < expected / 2) {
            throw new IllegalStateException(String.format(
                    "seed matrix %s resolved only %d of %d cells; labels do not line up",
                    seedPath, resolved, expected));
        }
        log.info("Seeded SF from {} ({} of {} cells)", seedPath, resolved, expected);
        return out;
    }

    /**
     * Reads one cell of a similarity matrix in either format Schematch writes.
     *
     * With saveOutputVerbose the cell is "&lt;selected&gt; / &lt;score&gt;", where the
     * first number is the binary decision and the second the soft score; plain
     * output carries the score alone. Only the score is wanted here. The
     * harness reader has handled both since the beginning -- this side had not,
     * and every cell of a verbose file failed to parse.
     */
    private static float parseCell(String raw) {
        String cell = raw.trim();
        int slash = cell.indexOf('/');
        if (slash >= 0) {
            cell = cell.substring(slash + 1).trim();
        }
        return Float.parseFloat(cell);
    }

    private static String normaliseLabel(String raw) {
        String label = raw.trim();
        if (label.startsWith("\"") && label.endsWith("\"") && label.length() > 1) {
            label = label.substring(1, label.length() - 1);
        }
        int dot = label.lastIndexOf('.');
        return dot >= 0 ? label.substring(dot + 1) : label;
    }

    private static List<String> labelsOf(Database db) {
        List<String> out = new ArrayList<>();
        for (Table table : db.getTables()) {
            for (Column column : table.getColumns()) {
                out.add(column.getLabel());
            }
        }
        return out;
    }

    private Map<NodePair, Double> buildInitialMappingFromMatrix(float[][] simMatrix, Database sourceDb, Database targetDb) {
        Map<NodePair, Double> initialMapping = new HashMap<>();

        for (Table sourceTable : sourceDb.getTables()) {
            for (Table targetTable : targetDb.getTables()) {

                List<Column> sourceColumns = sourceTable.getColumns();
                List<Column> targetColumns = targetTable.getColumns();

                for (int i = 0; i < sourceColumns.size(); i++) {

                    String sourceLabel = sourceColumns.get(i).getLabel();
                    Node sourceNode = new Node(sourceLabel, NodeType.COLUMN, null, false, null, sourceTable, null);

                    for (int j = 0; j < targetColumns.size(); j++) {

                        String targetLabel = targetColumns.get(j).getLabel();
                        Node targetNode = new Node(targetLabel, NodeType.COLUMN, null, false, null, targetTable, null);

                        float value = simMatrix[sourceTable.getOffset() + i] [targetTable.getOffset() + j];
                        initialMapping.put(new NodePair(sourceNode,targetNode), (double) value);
                    }
                }
            }
        }

        return initialMapping;
    }

    public enum Param {
        PROP_COEFF_POLICY("propCoeffPolicy", List.of("INV_AVG", "INV_PROD")),
        FIXPOINT("fixpoint", List.of("BASIC","A", "B", "C")),
        IND_FILTER_THRESHOLD("indFilterThreshold", List.of("normalizedValue")),
        FD_FILTER_THRESHOLD("fdFilterThreshold", List.of("normalizedValue")),
        UCC_FILTER_THRESHOLD("uccFilterThreshold", List.of("normalizedValue")),
        CONSTRAINT_WEIGHTING("constraintWeighting", List.of("NONE", "QUALITY")),
        GRAPH_ENCODING("graphEncoding", List.of("COMPLETE", "QUICK")),
        DEPENDENCY_TOP_K("dependencyTopK", List.of("positiveIntegerOrEmpty")),
        FD_ALLOW_LIST_FILE("fdAllowListFile", List.of("fileNameOrEmpty")),
        INITIAL_MATRIX("initialMatrix", List.of("PREVIOUS", "RAW_FIRST_LINE"));

        public final String key;
        public final Collection<String> possibleValues;

        Param(String key, Collection<String> possibleValues) {
            this.key = key;
            this.possibleValues = possibleValues;
        }
    }

}
