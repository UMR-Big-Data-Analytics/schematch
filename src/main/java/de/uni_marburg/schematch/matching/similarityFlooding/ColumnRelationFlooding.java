package de.uni_marburg.schematch.matching.similarityFlooding;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Database;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.data.metadata.dependency.FunctionalDependency;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jgrapht.Graph;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Experimental SF encodings over column identities, with no schema/type hubs.
 *
 * Each relation has its own adjacency matrix. Product edges are kept as typed
 * parallel messages in CSR rather than put in a simple graph that silently
 * rejects a second edge. Both incoming and outgoing roles are preserved.
 * The existing SF fixpoint formulas and global maximum normalisation are reused;
 * this is not a new damped/residual update. A column pair with no structural
 * messages retains its seed, and an entirely uninformative graph is a no-op.
 */
final class ColumnRelationFlooding {
    private static final Logger log = LogManager.getLogger(ColumnRelationFlooding.class);
    private static final Set<String> ENCODINGS = Set.of(
            "COLUMN_ONLY", "RELATIONAL", "RELATIONAL_IND",
            "RELATIONAL_FD_IND", "RELATIONAL_FD_IND_U", "RELATIONAL_FD_IND_U_GATE",
            "RELATIONAL_FD_JACCARD", "RELATIONAL_FD_JACCARD_U_GATE");
    private static final Set<String> NULLS = Set.of("", "null", "none", "nan", "na", "n/a", "-");
    // Numerical zero, not a selected dependency-discovery threshold.
    private static final double ROUND_OFF = 1e-12;

    record Channel(String type, double[][] weights) { }
    record Arc(int source, double coefficient) { }
    record Propagation(int[] rowPtr, int[] sources, double[] coefficients) { }

    private ColumnRelationFlooding() { }

    static boolean supports(String encoding) {
        return ENCODINGS.contains(Objects.toString(encoding, ""));
    }

    static float[][] match(Database source, Database target, float[][] seed,
                           String encoding, PropagationCoefficientPolicy policy,
                           FixpointFormula formula, String fdThreshold, String weighting,
                           String topK, String allowList, String nullMaskDir) {
        return match(source, target, seed, encoding, policy, formula, fdThreshold, weighting, topK, allowList,
                nullMaskDir, GraphOptions.defaults());
    }

    /** As above, with the dependency settings (thinning, rewiring, weighting constants) of one SF run. */
    static float[][] match(Database source, Database target, float[][] seed,
                           String encoding, PropagationCoefficientPolicy policy,
                           FixpointFormula formula, String fdThreshold, String weighting,
                           String topK, String allowList, String nullMaskDir, GraphOptions options) {
        List<Channel> left = build(source, encoding, fdThreshold, weighting, topK, allowList, nullMaskDir, options);
        List<Channel> right = build(target, encoding, fdThreshold, weighting, topK, allowList, nullMaskDir, options);
        double[][] initial = new double[seed.length][];
        for (int i = 0; i < seed.length; i++) {
            initial[i] = new double[seed[i].length];
            for (int j = 0; j < seed[i].length; j++) initial[i][j] = seed[i][j];
        }
        double[][] result = propagate(initial, left, right, policy, formula);
        float[][] out = new float[result.length][];
        for (int i = 0; i < result.length; i++) {
            out[i] = new float[result[i].length];
            for (int j = 0; j < result[i].length; j++) out[i][j] = (float) result[i][j];
        }
        return out;
    }

    private static List<Channel> build(Database db, String encoding, String fdThreshold,
                                       String weighting, String topK, String allowList,
                                       String nullMaskDir, GraphOptions options) {
        int width = db.getNumColumns();
        if ("COLUMN_ONLY".equals(encoding)) {
            // Extract precisely the projected FD evidence in QUICK. In
            // particular, do not silently turn this control into unary-only FDs.
            Graph<Node, LabelEdge> quick = new SchemaGraphBuilder("", "", fdThreshold,
                    weighting, "QUICK", topK, allowList, options).transformIntoGraphRepresentationSchema(db);
            IdentityHashMap<Column, Integer> index = new IdentityHashMap<>();
            for (Table table : db.getTables()) {
                for (int i = 0; i < table.getNumColumns(); i++) index.put(table.getColumn(i), table.getOffset() + i);
            }
            double[][] fd = new double[width][width];
            for (LabelEdge edge : quick.edgeSet()) {
                if (!"determines".equals(edge.getLabel())) continue;
                Column a = quick.getEdgeSource(edge).getNameNode().getRepresentedColumn();
                Column b = quick.getEdgeTarget(edge).getNameNode().getRepresentedColumn();
                int i = index.get(a), j = index.get(b);
                if (i != j) fd[i][j] = edge.getWeight();
            }
            return List.of(new Channel("projected-fd", fd));
        }

        if (encoding.startsWith("RELATIONAL_FD_")) {
            Graph<Node, LabelEdge> quick = new SchemaGraphBuilder("", "", fdThreshold,
                    weighting, "QUICK", topK, allowList, options).transformIntoGraphRepresentationSchema(db);
            IdentityHashMap<Column, Integer> index = new IdentityHashMap<>();
            for (Table table : db.getTables()) {
                for (int i = 0; i < table.getNumColumns(); i++) {
                    index.put(table.getColumn(i), table.getOffset() + i);
                }
            }
            double[][] fd = new double[width][width];
            for (LabelEdge edge : quick.edgeSet()) {
                if (!"determines".equals(edge.getLabel())) continue;
                Column a = quick.getEdgeSource(edge).getNameNode().getRepresentedColumn();
                Column b = quick.getEdgeTarget(edge).getNameNode().getRepresentedColumn();
                int i = index.get(a), j = index.get(b);
                if (i != j) fd[i][j] = edge.getWeight();
            }

            double[][] overlap = new double[width][width];
            double[][] uncertainty = new double[width][width];
            boolean useJaccard = encoding.contains("JACCARD");
            boolean useUncertainty = encoding.contains("_U");
            boolean gate = encoding.endsWith("_GATE");
            for (Table table : db.getTables()) {
                boolean[][] valid = readMask(db, table, nullMaskDir);
                double[][] localOverlap = useJaccard
                        ? withinJaccard(table.getColumns(), valid)
                        : withinIndGcov(table.getColumns(), valid);
                double[][] localUncertainty = useUncertainty
                        ? uncertainty(table.getColumns(), valid) : null;
                for (int i = 0; i < table.getNumColumns(); i++) {
                    for (int j = 0; j < table.getNumColumns(); j++) {
                        int a = table.getOffset() + i, b = table.getOffset() + j;
                        overlap[a][b] = gate
                                ? localOverlap[i][j] * localUncertainty[i][j]
                                : localOverlap[i][j];
                        if (useUncertainty && !gate) uncertainty[a][b] = localUncertainty[i][j];
                    }
                }
            }
            List<Channel> channels = new ArrayList<>();
            channels.add(new Channel("projected-fd", fd));
            channels.add(new Channel(useJaccard ? "within-jaccard" : "within-ind-gcov", overlap));
            if (useUncertainty && !gate) channels.add(new Channel("within-uncertainty", uncertainty));
            return channels;
        }

        double[][] gpdep = new double[width][width];
        double[][] gcov = new double[width][width];
        for (Table table : db.getTables()) {
            double[][] localGpdep = positiveGpdep(table.getColumns());
            double[][] localGcov = "RELATIONAL_IND".equals(encoding)
                    ? withinIndGcov(table.getColumns(), readMask(db, table, nullMaskDir)) : null;
            for (int i = 0; i < table.getNumColumns(); i++) {
                for (int j = 0; j < table.getNumColumns(); j++) {
                    gpdep[table.getOffset() + i][table.getOffset() + j] = localGpdep[i][j];
                    if (localGcov != null) gcov[table.getOffset() + i][table.getOffset() + j] = localGcov[i][j];
                }
            }
        }
        List<Channel> channels = new ArrayList<>();
        channels.add(new Channel("gpdep", gpdep));
        if ("RELATIONAL_IND".equals(encoding)) channels.add(new Channel("within-ind-gcov", gcov));
        return channels;
    }

    static double[][] positiveGpdep(List<Column> columns) {
        int n = columns.size();
        double[][] result = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j || columns.get(j).getValues().size() < 2) continue;
                if (columns.get(i).getValues().size() != columns.get(j).getValues().size()) {
                    throw new IllegalArgumentException("gpdep requires aligned rows within one table");
                }
                double score = new FunctionalDependency(List.of(columns.get(i)), columns.get(j)).calculateGPDEPScore();
                result[i][j] = Double.isFinite(score) && score > ROUND_OFF ? score : 0.0;
            }
        }
        return result;
    }

    /** Non-null validity is column-major. No plaintext values are needed. */
    static double[][] withinIndGcov(List<Column> columns, boolean[][] valid) {
        int n = columns.size();
        if (valid != null && valid.length != n) throw new IllegalArgumentException("null mask width mismatch");
        List<Map<String, Integer>> counts = new ArrayList<>();
        Map<String, Integer> total = new HashMap<>();
        int[] sizes = new int[n];
        int totalSize = 0;
        for (int i = 0; i < n; i++) {
            List<String> values = columns.get(i).getValues();
            if (valid != null && valid[i].length != values.size()) throw new IllegalArgumentException("null mask height mismatch");
            Map<String, Integer> counter = new HashMap<>();
            for (int row = 0; row < values.size(); row++) {
                String value = values.get(row);
                boolean retain = valid != null ? valid[i][row] : !NULLS.contains(value.trim().toLowerCase(Locale.ROOT));
                if (!retain) continue;
                counter.merge(value, 1, Integer::sum);
                total.merge(value, 1, Integer::sum);
                sizes[i]++;
                totalSize++;
            }
            counts.add(counter);
        }
        double[][] out = new double[n][n];
        for (int i = 0; i < n; i++) {
            Map<String, Integer> dependent = counts.get(i);
            int backgroundSize = totalSize - sizes[i];
            if (dependent.isEmpty() || backgroundSize == 0) continue;
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                int matched = 0, backgroundCovered = 0;
                for (String value : counts.get(j).keySet()) {
                    if (dependent.containsKey(value)) matched++;
                    backgroundCovered += total.get(value) - dependent.getOrDefault(value, 0);
                }
                if (matched == 0 || backgroundCovered >= backgroundSize) continue;
                double b = backgroundCovered / (double) backgroundSize;
                double strength = (matched / (double) dependent.size() - b) / (1.0 - b);
                out[i][j] = strength > ROUND_OFF ? Math.min(1.0, strength) : 0.0;
            }
        }
        return out;
    }

    static double[][] withinJaccard(List<Column> columns, boolean[][] valid) {
        int n = columns.size();
        if (valid != null && valid.length != n) throw new IllegalArgumentException("null mask width mismatch");
        List<Set<String>> sets = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<String> values = columns.get(i).getValues();
            if (valid != null && valid[i].length != values.size()) throw new IllegalArgumentException("null mask height mismatch");
            Set<String> retained = new HashSet<>();
            for (int row = 0; row < values.size(); row++) {
                String value = values.get(row);
                boolean keep = valid != null ? valid[i][row] : !NULLS.contains(value.trim().toLowerCase(Locale.ROOT));
                if (keep) retained.add(value);
            }
            sets.add(retained);
        }
        double[][] out = new double[n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            if (i == j) continue;
            Set<String> intersection = new HashSet<>(sets.get(i));
            intersection.retainAll(sets.get(j));
            if (intersection.isEmpty()) continue;
            Set<String> union = new HashSet<>(sets.get(i));
            union.addAll(sets.get(j));
            out[i][j] = intersection.size() / (double) union.size();
        }
        return out;
    }

    /** U(Y|X)=I(X;Y)/H(Y), using pair-complete non-null rows. */
    static double[][] uncertainty(List<Column> columns, boolean[][] valid) {
        int n = columns.size();
        if (valid != null && valid.length != n) throw new IllegalArgumentException("null mask width mismatch");
        double[][] out = new double[n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            if (i == j) continue;
            List<String> xs = columns.get(i).getValues(), ys = columns.get(j).getValues();
            if (xs.size() != ys.size()) throw new IllegalArgumentException("uncertainty requires aligned rows within one table");
            if (valid != null && (valid[i].length != xs.size() || valid[j].length != ys.size())) {
                throw new IllegalArgumentException("null mask height mismatch");
            }
            Map<String, Integer> countX = new HashMap<>(), countY = new HashMap<>();
            Map<List<String>, Integer> joint = new HashMap<>();
            int support = 0;
            for (int row = 0; row < xs.size(); row++) {
                String x = xs.get(row), y = ys.get(row);
                boolean keepX = valid != null ? valid[i][row] : !NULLS.contains(x.trim().toLowerCase(Locale.ROOT));
                boolean keepY = valid != null ? valid[j][row] : !NULLS.contains(y.trim().toLowerCase(Locale.ROOT));
                if (!keepX || !keepY) continue;
                countX.merge(x, 1, Integer::sum);
                countY.merge(y, 1, Integer::sum);
                joint.merge(List.of(x, y), 1, Integer::sum);
                support++;
            }
            if (support < 2 || countY.size() < 2) continue;
            double entropyY = 0.0, mutualInformation = 0.0;
            for (int count : countY.values()) {
                double p = count / (double) support;
                entropyY -= p * Math.log(p);
            }
            if (entropyY <= ROUND_OFF) continue;
            for (Map.Entry<List<String>, Integer> entry : joint.entrySet()) {
                double pxy = entry.getValue() / (double) support;
                double px = countX.get(entry.getKey().get(0)) / (double) support;
                double py = countY.get(entry.getKey().get(1)) / (double) support;
                mutualInformation += pxy * Math.log(pxy / (px * py));
            }
            double score = mutualInformation / entropyY;
            out[i][j] = Double.isFinite(score) && score > ROUND_OFF
                    ? Math.min(1.0, Math.max(0.0, score)) : 0.0;
        }
        return out;
    }

    private static boolean[][] readMask(Database db, Table table, String maskDir) {
        if (maskDir == null || maskDir.isBlank()) return null;
        Path databasePath = Path.of(db.getPath());
        Path scenario = databasePath.getParent();
        Path path = Path.of(maskDir).resolve(scenario.getParent().getFileName())
                .resolve(scenario.getFileName()).resolve(db.getName()).resolve(table.getName() + ".csv");
        boolean[][] mask = new boolean[table.getNumColumns()][];
        for (int i = 0; i < mask.length; i++) mask[i] = new boolean[table.getColumn(i).getValues().size()];
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = new CSVParser(reader, format)) {
            List<String> header = parser.getHeaderNames();
            if (header.size() != table.getNumColumns() || new HashSet<>(header).size() != header.size()) {
                throw new IllegalArgumentException("invalid null mask header: " + path);
            }
            for (Column column : table.getColumns()) {
                if (!header.contains(column.getLabel())) throw new IllegalArgumentException("null mask missing column: " + column.getLabel());
            }
            int row = 0;
            for (CSVRecord record : parser) {
                if (record.size() != header.size()) throw new IllegalArgumentException("ragged null mask: " + path);
                for (int i = 0; i < table.getNumColumns(); i++) {
                    if (row >= mask[i].length) throw new IllegalArgumentException("null mask too long: " + path);
                    String bit = record.get(table.getColumn(i).getLabel());
                    if (!bit.equals("0") && !bit.equals("1")) throw new IllegalArgumentException("non-binary null mask: " + path);
                    mask[i][row] = bit.equals("1");
                }
                row++;
            }
            for (boolean[] column : mask) {
                if (column.length != row) throw new IllegalArgumentException("null mask too short: " + path);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read owner-supplied null mask " + path, e);
        }
        return mask;
    }

    private static int[] degrees(double[][] weights, boolean incoming) {
        int[] count = new int[weights.length];
        for (int i = 0; i < weights.length; i++) {
            if (weights[i].length != weights.length) throw new IllegalArgumentException("relation matrix must be square");
            for (int j = 0; j < weights.length; j++) {
                double w = weights[i][j];
                if (!Double.isFinite(w) || w < 0 || w > 1) throw new IllegalArgumentException("invalid relation strength");
                if (i == j && w != 0) throw new IllegalArgumentException("trivial self-relations must be removed");
                if (w > 0) count[incoming ? j : i]++;
            }
        }
        return count;
    }

    static Propagation connectivity(int n, int m, List<Channel> left, List<Channel> right,
                                    PropagationCoefficientPolicy policy) {
        if (policy != PropagationCoefficientPolicy.INVERSE_PRODUCT && policy != PropagationCoefficientPolicy.INVERSE_AVERAGE) {
            throw new IllegalArgumentException("unsupported relational propagation policy: " + policy);
        }
        Map<String, Channel> target = new TreeMap<>();
        for (Channel channel : right) {
            if (channel.weights.length != m || target.put(channel.type, channel) != null) {
                throw new IllegalArgumentException("duplicate or wrong-width target relation channel");
            }
        }
        List<Channel> ordered = new ArrayList<>(left);
        ordered.sort(Comparator.comparing(Channel::type));
        Set<String> seen = new HashSet<>();
        int active = 0;
        for (Channel channel : ordered) {
            if (channel.weights.length != n || !seen.add(channel.type)) throw new IllegalArgumentException("duplicate or wrong-width source channel");
            if (target.containsKey(channel.type)
                    && Arrays.stream(degrees(channel.weights, false)).sum() > 0
                    && Arrays.stream(degrees(target.get(channel.type).weights, false)).sum() > 0) active++;
        }
        List<List<Arc>> incoming = new ArrayList<>(n * m);
        for (int i = 0; i < n * m; i++) incoming.add(new ArrayList<>());
        for (Channel channel : ordered) {
            Channel other = target.get(channel.type);
            if (other == null) continue;
            double[][] a = channel.weights, b = other.weights;
            int[] outA = degrees(a, false), inA = degrees(a, true);
            int[] outB = degrees(b, false), inB = degrees(b, true);
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
                if (a[i][j] == 0) continue;
                for (int k = 0; k < m; k++) for (int l = 0; l < m; l++) {
                    if (b[k][l] == 0) continue;
                    int from = i * m + k, to = j * m + l;
                    double confidence = Math.min(a[i][j], b[k][l]) / active;
                    double forward = policy == PropagationCoefficientPolicy.INVERSE_PRODUCT
                            ? 1.0 / (outA[i] * (double) outB[k]) : 2.0 / (outA[i] + outB[k]);
                    double backward = policy == PropagationCoefficientPolicy.INVERSE_PRODUCT
                            ? 1.0 / (inA[j] * (double) inB[l]) : 2.0 / (inA[j] + inB[l]);
                    incoming.get(to).add(new Arc(from, confidence * forward));
                    incoming.get(from).add(new Arc(to, confidence * backward));
                }
            }
        }
        int[] rowPtr = new int[n * m + 1];
        for (int i = 0; i < n * m; i++) rowPtr[i + 1] = rowPtr[i] + incoming.get(i).size();
        int[] sources = new int[rowPtr[n * m]];
        double[] coefficients = new double[sources.length];
        for (int i = 0; i < n * m; i++) {
            int position = rowPtr[i];
            for (Arc arc : incoming.get(i)) {
                sources[position] = arc.source;
                coefficients[position++] = arc.coefficient;
            }
        }
        return new Propagation(rowPtr, sources, coefficients);
    }

    static double[][] propagate(double[][] seed, List<Channel> left, List<Channel> right,
                                PropagationCoefficientPolicy policy, FixpointFormula formula) {
        int n = seed.length, m = n == 0 ? 0 : seed[0].length;
        double[] initial = new double[n * m];
        for (int i = 0; i < n; i++) {
            if (seed[i].length != m) throw new IllegalArgumentException("ragged seed matrix");
            for (int j = 0; j < m; j++) {
                double value = seed[i][j];
                if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("invalid seed score");
                initial[i * m + j] = value;
            }
        }
        Propagation graph = connectivity(n, m, left, right, policy);
        double[] current = initial.clone();
        boolean[] dependencyPairs = new boolean[initial.length];
        int iterations = 0;
        if (graph.sources.length > 0) for (; iterations <= 200; iterations++) {
            double[] next = new double[current.length];
            double maximum = 0.0;
            for (int i = 0; i < current.length; i++) {
                next[i] = graph.rowPtr[i] == graph.rowPtr[i + 1] ? initial[i]
                        : formula.evaluate(i, initial, current, graph.rowPtr, graph.sources, graph.coefficients, dependencyPairs);
                maximum = Math.max(maximum, next[i]);
            }
            if (maximum > 0) for (int i = 0; i < next.length; i++) next[i] /= maximum;
            boolean converged = SimilarityFloodingUtils.hasConverged(current, next, 0.0001);
            current = next;
            if (converged) { iterations++; break; }
        }
        double[][] result = new double[n][m];
        for (int i = 0; i < n * m; i++) {
            result[i / m][i % m] = graph.rowPtr[i] == graph.rowPtr[i + 1] ? initial[i] : current[i];
        }
        log.info("Column-relation SF: {} pairs, {} typed messages, {} iterations", n * m, graph.sources.length, iterations);
        return result;
    }
}
