package de.uni_marburg.schematch.matching.similarityFlooding;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Database;
import de.uni_marburg.schematch.data.Table;
import org.jgrapht.Graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static de.uni_marburg.schematch.matching.similarityFlooding.SchemaGraphBuilder.createConnectivityGraph;
import static de.uni_marburg.schematch.matching.similarityFlooding.SchemaGraphBuilder.inducePropagationGraph;

/**
 * The literal evidence of SF as published, computed in one step instead of by iterating.
 *
 * <p>The graph is SF's: Melnik's schema graph without dependency edges (QUICK encoding, no dependency
 * thresholds), inverse-average coefficients, the initial matrix on the column-name pairs and the
 * prefix/suffix similarity on every other pair of non-ID nodes (the literal seeds ell, as with
 * -Dsmx.sf.literalSeed=PREFIX_SUFFIX). With P the propagation matrix, L = (P^2 ell) on the scored
 * column-ID pairs: the literal evidence SF's second update adds (sf-matcher-study X93,
 * layer3b/score_x93.py literal_term). L does not depend on the initial matrix.
 *
 * <p>M1 is the divisor of SF's first update under formula C, the maximum over all node pairs of
 * 2 sigma0 + P (2 sigma0) with sigma0 the full seed. Two updates of SF rank the column pairs of a single
 * table pair like q + c L with c = 2 / (M1 + 4) (layer3b/x94_stage1.py; review_checks/two_update_offset_check.py).
 */
public final class LiteralEvidence {
    private LiteralEvidence() {}

    /** L on the scenario's column pairs (source rows, target columns) and M1. */
    public record Result(double[][] literal, double firstUpdateMaximum) {}

    /** @param scores the initial matrix; only M1 depends on it, null seeds the name pairs with 0 */
    public static Result compute(Database source, Database target, float[][] scores) {
        SchemaGraphBuilder builder = new SchemaGraphBuilder("", "", "", "NONE", "QUICK", "", "");
        Graph<Node, LabelEdge> sourceGraph = builder.transformIntoGraphRepresentationSchema(source);
        Graph<Node, LabelEdge> targetGraph = builder.transformIntoGraphRepresentationSchema(target);
        Graph<NodePair, CoefficientEdge> propagation = inducePropagationGraph(
                createConnectivityGraph(sourceGraph, targetGraph), sourceGraph, targetGraph,
                PropagationCoefficientPolicy.INVERSE_AVERAGE);

        float[][] q = scores != null ? scores : new float[source.getNumColumns()][target.getNumColumns()];
        Map<NodePair, Double> seeds = SimilarityFlooding.buildInitialMappingFromMatrix(q, source, target);
        Set<NodePair> matrixKeys = new HashSet<>(seeds.keySet());
        SimilarityFlooding.addLiteralSeeds(propagation, seeds);

        // Index and incoming CSR exactly as SimilarityFlooding's fixpoint loop builds them.
        List<NodePair> nodes = new ArrayList<>(propagation.vertexSet());
        int n = nodes.size();
        Map<NodePair, Integer> indexOf = new HashMap<>(Math.max(16, n * 2));
        for (int i = 0; i < n; i++) indexOf.put(nodes.get(i), i);
        int[] rowPtr = new int[n + 1];
        for (int i = 0; i < n; i++) rowPtr[i + 1] = rowPtr[i] + propagation.incomingEdgesOf(nodes.get(i)).size();
        int[] src = new int[rowPtr[n]];
        double[] coeff = new double[rowPtr[n]];
        for (int i = 0; i < n; i++) {
            int k = rowPtr[i];
            for (CoefficientEdge edge : propagation.incomingEdgesOf(nodes.get(i))) {
                src[k] = indexOf.get(propagation.getEdgeSource(edge));
                coeff[k++] = edge.getCoefficient();
            }
        }

        double[] sigma0 = new double[n], literal = new double[n];
        for (int i = 0; i < n; i++) {
            sigma0[i] = seeds.getOrDefault(nodes.get(i), 0.0);
            literal[i] = matrixKeys.contains(nodes.get(i)) ? 0.0 : sigma0[i];
        }
        double[] twoStep = propagate(propagate(literal, rowPtr, src, coeff), rowPtr, src, coeff);
        double[] doubled = new double[n];
        for (int i = 0; i < n; i++) doubled[i] = 2 * sigma0[i];
        double[] firstUpdate = propagate(doubled, rowPtr, src, coeff);
        double m1 = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) m1 = Math.max(m1, doubled[i] + firstUpdate[i]);

        Map<NodePair, Double> byNode = new HashMap<>(Math.max(16, n * 2));
        for (int i = 0; i < n; i++) byNode.put(nodes.get(i), twoStep[i]);
        return new Result(toMatrix(SimilarityFloodingUtils.filterMapping(byNode), source, target), m1);
    }

    private static double[] propagate(double[] x, int[] rowPtr, int[] src, double[] coeff) {
        double[] out = new double[rowPtr.length - 1];
        for (int node = 0; node < out.length; node++) {
            double sum = 0.0;
            for (int k = rowPtr[node]; k < rowPtr[node + 1]; k++) sum += coeff[k] * x[src[k]];
            out[node] = sum;
        }
        return out;
    }

    /** SimilarityFloodingUtils.populateSimMatrix without the float rounding. */
    private static double[][] toMatrix(Map<NodePair, Double> byNamePair, Database source, Database target) {
        double[][] out = new double[source.getNumColumns()][target.getNumColumns()];
        for (Table s : source.getTables()) {
            for (Table t : target.getTables()) {
                List<Column> sc = s.getColumns(), tc = t.getColumns();
                for (int i = 0; i < sc.size(); i++) {
                    Node a = new Node(sc.get(i).getLabel(), NodeType.COLUMN, null, false, null, s, null);
                    for (int j = 0; j < tc.size(); j++) {
                        Node b = new Node(tc.get(j).getLabel(), NodeType.COLUMN, null, false, null, t, null);
                        out[s.getOffset() + i][t.getOffset() + j] = byNamePair.getOrDefault(new NodePair(a, b), 0.0);
                    }
                }
            }
        }
        return out;
    }
}
