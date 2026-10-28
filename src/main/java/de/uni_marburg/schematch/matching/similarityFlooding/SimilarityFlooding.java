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
    // Residual threshold (Euclidean length of sigma^n - sigma^(n-1), as in Melnik et al. 2002, who
    // use 0.05). Absent the property the value is 1e-4, the production value.
    private static final double PRECISION_OF_CONVERGENCE =
            Double.parseDouble(System.getProperty("smx.sf.epsilon", "0.0001"));

    /**
     * Initial similarity of node pairs that are not column-name pairs. NONE (default, production):
     * only column-name pairs carry the seed matrix, every other pair starts at 0. PREFIX_SUFFIX:
     * as in Melnik et al. 2002 (Table 1), every pair of non-ID nodes (table, schema and data-type
     * names, and the Table/Column/ColumnType type nodes) starts at the prefix/suffix string
     * similarity of its labels; column-name pairs keep the seed matrix.
     */
    private static final String LITERAL_SEED =
            System.getProperty("smx.sf.literalSeed", "NONE").toUpperCase();

    /** Iterations and convergence of the last fixpoint run on this thread, for the SFSTATS line. */
    private static final ThreadLocal<int[]> LAST_RUN = ThreadLocal.withInitial(() -> new int[]{-1, -1});

    private static void recordRun(int iterations, boolean converged) {
        LAST_RUN.set(new int[]{iterations, converged ? 1 : 0});
    }
    // Overridable for the registered graph-construction screen
    // (SF_GRAPH_CONSTRUCTION.md). Absent the property the value is 200, so the
    // production path is byte-identical to before this line existed.
    private static final int MAX_ITERATIONS =
            Integer.getInteger("smx.sf.maxIterations", 200);

    /**
     * How each iteration is rescaled (SF_NORMALISATION.md).
     *
     * GLOBAL is the historical behaviour: divide by the largest value over ALL
     * node pairs, including table, datatype and constraint pairs that the output
     * matrix never reads. sigma_0 is never rescaled, so the balance between the
     * seed term and the propagated term moves with whatever the normaliser
     * happens to be.
     *
     * COLUMN divides by the largest value among column-column pairs -- the ones
     * actually scored. NONE does not rescale at all. Absent the property the
     * value is GLOBAL and the production path is unchanged.
     */
    /**
     * How each iteration is rescaled (SF_NORMALISER.md).
     *
     * GLOBAL is canonical Similarity Flooding: Melnik et al. divide every
     * similarity by the maximal value of the current iteration.
     *
     * The others exist to separate "max-normalisation specifically" from
     * "state-dependent rescaling in general". Writing N(y) = y/c(y),
     *
     *     DN(y) = I/c(y) - y grad c(y)^T / c(y)^2,
     *
     * so each contributes a rank-one correction whose GEOMETRY differs:
     * for MAX grad c = e_k and the correction I - y e_k^T / c is an OBLIQUE
     * projector; for L2 grad c = y/||y|| and the correction I - yy^T/||y||^2 is
     * an ORTHOGONAL projector, of spectral norm exactly 1.
     *
     * Note these change the MAP, not merely the trajectory, so unlike
     * relaxation they can move the fixed point. They are SF variants, not
     * diagnostic corrections, and matching quality has to be measured for them.
     */
    /**
     * CWISE gives every WEAKLY CONNECTED COMPONENT of the propagation graph its
     * own divisor, instead of sharing one across the whole state.
     *
     * Global max-normalisation makes SF non-local: an independently seeded
     * component with no edge to the matching problem can change which
     * correspondences are selected, because it competes for the shared divisor
     * (SF_COMPONENTS.md). CWISE removes that by construction -- a component's
     * scale can no longer be set from outside it.
     *
     * It is NOT a different matcher on these graphs. Every scored pair lies in
     * one component and the global argmax lies in that same component on 54 of
     * 54 graphs checked, so the divisor CWISE picks for the scored component is
     * the one GLOBAL was already using, and the scored block is bitwise
     * identical at every update (SF_SCORED_FIX.md). It diverges from GLOBAL only
     * where that precondition fails -- scored pairs spread over several
     * components, or an argmax outside the scored one -- which is exactly the
     * situation where GLOBAL's answer depends on unrelated structure.
     *
     * Unlike SCORED it does not change which nodes set the scale INSIDE the
     * matching component, which is why it does not carry SCORED's accuracy cost.
     */
    enum Normalise { GLOBAL, COLUMN, SCORED, L2, CWISE, NONE }

    private static final Normalise NORMALISE = Normalise.valueOf(
            System.getProperty("smx.sf.normalise", "GLOBAL").toUpperCase());

    /**
     * Scale the normalisation divisor: x <- A(q+x) / (alpha * c).
     *
     * A DOSE CONTROL on propagation strength, not a harmless rescale. Formula C
     * re-injects q un-normalised every update, so shrinking the state relative
     * to q shifts the balance between injected seed and propagated evidence. At
     * a fixed point of the MAX variant,
     *
     *     x = A(q+x) / (alpha * ||A(q+x)||_inf)   =>   ||x||_inf = 1/alpha
     *
     * exactly, while ||q||_inf = 1. So alpha sets the state-to-seed scale
     * analytically rather than empirically. The normaliser's geometry -- which
     * coordinates participate, and the rank-one term they induce -- is
     * untouched, which is what separates this from swapping the normaliser.
     */
    private static final double NORM_ALPHA =
            Double.parseDouble(System.getProperty("smx.sf.normaliseAlpha", "1.0"));

    /**
     * Where the supplied seed is attached (SF_RESTART.md, review item 1).
     *
     * NAME is the historical encoding: the seed lands on column *name* pairs,
     * while the output is read from column *ID* pairs, so it reaches the scored
     * correspondence through a graph edge. That edge has coefficient exactly
     * 1.0 in both directions, so the arrangement costs less than it looks -- but
     * it means alpha=0 under the restart update below would leave the scored
     * nodes at zero rather than at the seed, which is not a seed-only control.
     * ID attaches at the scored pairs instead; SPLIT halves the mass between
     * the two, so total seed mass is preserved in every setting.
     */
    enum SeedAttachment { NAME, ID, SPLIT }

    private static final SeedAttachment SEED_AT = SeedAttachment.valueOf(
            System.getProperty("smx.sf.seedAttachment", "NAME").toUpperCase());

    /**
     * Restart strength for the alternative update (review item 2).
     *
     * Empty, the default, runs the historical fixpoint. Set to a value in
     * [0,1) it runs
     *
     *     x[t+1] = (1 - alpha) q + alpha P x[t]
     *
     * with P the incoming propagation coefficients row-normalised to sum to at
     * most one, and q the attached seed. That is a contraction in the maximum
     * norm with factor alpha, so it has a unique fixed point, needs no
     * per-iteration rescaling, and -- unlike the historical update -- leaves
     * rankings unchanged when the seed is uniformly rescaled.
     *
     * alpha is the explicit version of a quantity the historical update leaves
     * implicit: how much of the answer comes from propagated structure rather
     * than from the evidence supplied. FINDINGS 98 and 103 both suggest the
     * data prefers that quantity small; this makes it measurable instead of
     * inferred from iteration counts.
     */
    private static final String RESTART_ALPHA_RAW =
            System.getProperty("smx.sf.restartAlpha", "");
    private static final boolean RESTART = !RESTART_ALPHA_RAW.isEmpty();
    private static final double RESTART_ALPHA =
            RESTART ? Double.parseDouble(RESTART_ALPHA_RAW) : 0.0;

    static {
        if (RESTART && (RESTART_ALPHA < 0.0 || RESTART_ALPHA >= 1.0)) {
            throw new IllegalArgumentException(
                    "smx.sf.restartAlpha must lie in [0,1); alpha >= 1 is not a contraction");
        }
    }

    /**
     * Review item 3: give each relation and direction a controlled budget.
     *
     * INV_PROD normalises by per-label degrees but imposes no total budget
     * across relation types, so structural mass is driven by how many edges an
     * encoding happens to create. This builds one row-substochastic operator
     * P_l per (label, direction) channel and combines them as
     * P = sum_l beta_l P_l with fixed non-negative beta_l summing to at most
     * one.
     *
     * UNIFORM gives every channel present in the graph 1/L. NO_FD gives the
     * same 1/L to every channel except `determines`, whose share is **left
     * unused rather than redistributed** -- the review is explicit that missing
     * channel mass must not be silently reallocated, since reallocating it
     * would cancel the very reduction the arm is testing.
     *
     * Only meaningful with the restart update, which is where P lives.
     */
    enum ChannelBudget { OFF, UNIFORM, NO_FD }

    private static final ChannelBudget BUDGET = ChannelBudget.valueOf(
            System.getProperty("smx.sf.channelBudget", "OFF").toUpperCase());

    /**
     * Review item 4: drop the message that comes straight back.
     *
     * The propagation graph carries an edge in both directions, so a value can
     * travel u->v->u and return as support without having acquired any
     * independent information. This replaces node values with per-edge messages
     * and excludes, from each message, the one arriving from its destination --
     * a non-backtracking walk. Other return paths through longer cycles are
     * retained, which is the point: only the immediate echo is removed.
     *
     * The naive form costs sum over u of in(u)*out(u); computing each node's
     * full incoming sum once and subtracting the returning term keeps it linear
     * in the edge count.
     */
    private static final boolean NON_BACKTRACKING =
            Boolean.getBoolean("smx.sf.nonBacktracking");

    /**
     * R8 (layer 3b, session C): restrict propagation to candidate column pairs.
     *
     * NONE, the default, changes nothing. Otherwise column pairs are pruned from the pairwise
     * connectivity graph by the initial matrix q before the propagation graph is induced: the
     * column-ID pair and its name pair are removed with all their edges, so a pruned pair neither
     * receives nor passes on similarity, and its output is 0.
     *   QUANTILE p: keep the pairs with q at or above the lower p-quantile of all column pairs of
     *               the scenario (ties at the threshold kept; p = 0 keeps everything).
     *   TOPK k:     keep a pair if it is among the k highest of its row or of its column (ties at
     *               the k-th value kept).
     * Added later (R8b, exploratory; the registered R8 runs use only QUANTILE and TOPK, whose code
     * path is unchanged):
     *   ROWTOPK k:    among the k highest of its row (source side) only;
     *   COLTOPK k:    among the k highest of its column (target side) only;
     *   MUTUALTOPK k: among the k highest of its row and of its column;
     *   GLOBALTOPK k: among the ceil(k * max(n, m)) highest cells of the whole matrix (k candidates
     *                 per column of the larger schema, on average; ties at the cut kept);
     *   ROWREL x:     q at or above x times its row's maximum (Melnik's relative similarity), x in (0, 1].
     * Propagation coefficients stay those of the two schema graphs (Melnik's inverse product or
     * average), so pruning removes mass rather than redistributing it. Every surviving column pair
     * keeps its local structure, so without dependency edges the survivors stay affine in q within
     * datatype blocks (Proposition 1); with dependency edges pruning changes which neighbours a pair
     * has. One machine-readable PAIRSCOPE line per scenario reports kept and total column pairs.
     * Schema-graph encodings only; the column-identity encodings reject it.
     */
    enum PairScope { NONE, QUANTILE, TOPK, ROWTOPK, COLTOPK, MUTUALTOPK, GLOBALTOPK, ROWREL }

    private static final PairScope PAIR_SCOPE = PairScope.valueOf(
            System.getProperty("smx.sf.pairScope", "NONE").toUpperCase());
    private static final double PAIR_SCOPE_PARAM =
            Double.parseDouble(System.getProperty("smx.sf.pairScopeParam", "0"));

    static {
        if (PAIR_SCOPE == PairScope.QUANTILE && (PAIR_SCOPE_PARAM < 0.0 || PAIR_SCOPE_PARAM >= 1.0)) {
            throw new IllegalArgumentException("smx.sf.pairScopeParam must lie in [0,1) for QUANTILE");
        }
        if (PAIR_SCOPE == PairScope.TOPK && (PAIR_SCOPE_PARAM < 1.0 || PAIR_SCOPE_PARAM != Math.rint(PAIR_SCOPE_PARAM))) {
            throw new IllegalArgumentException("smx.sf.pairScopeParam must be a positive integer for TOPK");
        }
        if ((PAIR_SCOPE == PairScope.ROWTOPK || PAIR_SCOPE == PairScope.COLTOPK || PAIR_SCOPE == PairScope.MUTUALTOPK)
                && (PAIR_SCOPE_PARAM < 1.0 || PAIR_SCOPE_PARAM != Math.rint(PAIR_SCOPE_PARAM))) {
            throw new IllegalArgumentException("smx.sf.pairScopeParam must be a positive integer for " + PAIR_SCOPE);
        }
        if (PAIR_SCOPE == PairScope.GLOBALTOPK && PAIR_SCOPE_PARAM <= 0.0) {
            throw new IllegalArgumentException("smx.sf.pairScopeParam must be positive for GLOBALTOPK");
        }
        if (PAIR_SCOPE == PairScope.ROWREL && (PAIR_SCOPE_PARAM <= 0.0 || PAIR_SCOPE_PARAM > 1.0)) {
            throw new IllegalArgumentException("smx.sf.pairScopeParam must lie in (0,1] for ROWREL");
        }
    }

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
    private String relationalNullMaskDir = "";

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
        // Column-column pairs are the only ones the output matrix ever reads.
        // SF_NORMALISATION.md uses this to normalise by what is scored rather
        // than by whichever auxiliary node pair happens to spike.
        boolean[] isColumnPair = new boolean[nodeCount];
        boolean[] isScoredPair = new boolean[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            NodePair node = nodes.get(i);
            sigma_0[i] = seedOf(node, initialMapping);
            isDependencyPair[i] = node.getFirstNode().getNodeType().equals(NodeType.CONSTRAINT)
                    && node.getSecondNode().getNodeType().equals(NodeType.CONSTRAINT);
            isColumnPair[i] = node.getFirstNode().getNodeType().equals(NodeType.COLUMN)
                    && node.getSecondNode().getNodeType().equals(NodeType.COLUMN);
            // Reporting only. isColumnPair is the production mask for
            // NORMALISE=COLUMN and admits name pairs as well as ID pairs; this
            // is the population the output is actually read from.
            isScoredPair[i] = node.getFirstNode().isIDNode() && node.getSecondNode().isIDNode()
                    && node.getFirstNode().getNodeType().equals(NodeType.COLUMN)
                    && node.getSecondNode().getNodeType().equals(NodeType.COLUMN);
        }

        // Compressed sparse row over incoming edges: rowPtr[i]..rowPtr[i+1]
        // are the edges flowing into node i.
        int[] rowPtr = new int[nodeCount + 1];
        for (int i = 0; i < nodeCount; i++) {
            rowPtr[i + 1] = rowPtr[i] + propagationGraph.incomingEdgesOf(nodes.get(i)).size();
        }
        int[] src = new int[rowPtr[nodeCount]];
        double[] coeff = new double[rowPtr[nodeCount]];
        // Channel of each incoming edge, as an index into channelNames. Only
        // the budgeted restart path reads it.
        int[] channelOf = new int[rowPtr[nodeCount]];
        List<String> channelNames = new ArrayList<>();
        Map<String, Integer> channelIndex = new HashMap<>();
        for (int i = 0; i < nodeCount; i++) {
            int k = rowPtr[i];
            for (CoefficientEdge edge : propagationGraph.incomingEdgesOf(nodes.get(i))) {
                src[k] = indexOf.get(propagationGraph.getEdgeSource(edge));
                coeff[k] = edge.getCoefficient();
                String name = edge.getChannel() == null ? "?" : edge.getChannel();
                channelOf[k] = channelIndex.computeIfAbsent(name, n -> {
                    channelNames.add(n);
                    return channelNames.size() - 1;
                });
                k++;
            }
        }

        // Weakly connected components of the propagation graph, by union-find.
        // Only CWISE divides by these, but the labels are cheap and the
        // diagnostic below is worth having on every run: it reports whether the
        // precondition under which CWISE and GLOBAL coincide actually holds.
        int[] componentOf = new int[nodeCount];
        int componentCount;
        {
            int[] parent = new int[nodeCount];
            for (int i = 0; i < nodeCount; i++) { parent[i] = i; }
            for (int i = 0; i < nodeCount; i++) {
                for (int k = rowPtr[i]; k < rowPtr[i + 1]; k++) {
                    int a = i, b = src[k];
                    while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a]; }
                    while (parent[b] != b) { parent[b] = parent[parent[b]]; b = parent[b]; }
                    if (a != b) { parent[a] = b; }
                }
            }
            Map<Integer, Integer> label = new HashMap<>();
            for (int i = 0; i < nodeCount; i++) {
                int r = i;
                while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r]; }
                componentOf[i] = label.computeIfAbsent(r, x -> label.size());
            }
            componentCount = label.size();
        }
        if (Boolean.getBoolean("smx.sf.printComponents")) {
            java.util.Set<Integer> scoredComps = new java.util.HashSet<>();
            for (int i = 0; i < nodeCount; i++) {
                if (isScoredPair[i]) { scoredComps.add(componentOf[i]); }
            }
            int[] size = new int[componentCount];
            for (int i = 0; i < nodeCount; i++) { size[componentOf[i]]++; }
            StringBuilder sizes = new StringBuilder();
            for (int s : size) { sizes.append(s).append(' '); }
            System.out.println("COMPONENTS n=" + nodeCount + " count=" + componentCount
                    + " scoredComponents=" + scoredComps.size()
                    + " sizes=" + sizes.toString().trim());
        }

        // Weighted contribution mass between node-pair populations. The proposed
        // near-bipartite explanation says similarity crosses between name pairs
        // and column-ID pairs on each update; that predicts the off-diagonal
        // blocks dominate. Reported on the operator the fixpoint consumes, so it
        // is a statement about the realised graph rather than the encoding.
        //
        // Note what this cannot settle on its own: formula C applies A = I + P,
        // and I + P annihilates the antisymmetric mode of an exact two-node
        // exchange, so bipartite structure alone does not produce alternation.
        // These blocks say where mass moves; they do not establish a mode.
        // Spectral scales of the propagation operator A = I + P.
        //
        // Two different bounds matter, and conflating them was a real error in
        // an earlier draft (SF_NORMALISER.md, reviewer correction):
        //
        //   rho(A)/c      bounds |lambda| ONLY for a mode that is an eigenvector
        //                 of A with no component on the maximiser, so the
        //                 rank-one normalisation term vanishes on it.
        //   sigma_max(A)/c  bounds |lambda| for ANY contractive (orthogonal)
        //                 projection of A, e.g. L2 normalisation, because
        //                 ||(I - x x^T) A||_2 <= ||A||_2 = sigma_max(A).
        //
        // For a NON-NORMAL A these differ, and sigma_max can greatly exceed rho.
        // So exceeding rho(A)/c shows only that the mode is not a rescaled
        // eigenmode; exceeding sigma_max(A)/c is what would show amplification
        // beyond anything an orthogonal projector could produce, which is the
        // oblique-projector claim.
        //
        // rho by power iteration on A; sigma_max by power iteration on A^T A.
        if (Boolean.getBoolean("smx.sf.printSpectrum")) {
            java.util.Random rng = new java.util.Random(20260919L);
            double[] v = new double[nodeCount];
            for (int i = 0; i < nodeCount; i++) { v[i] = rng.nextDouble() + 1e-9; }
            normalise2(v);
            double rho = 0.0;
            double[] u = new double[nodeCount];
            for (int it = 0; it < 400; it++) {
                applyA(v, u, nodeCount, rowPtr, src, coeff);
                rho = norm2(u);
                if (rho <= 0) { break; }
                for (int i = 0; i < nodeCount; i++) { v[i] = u[i] / rho; }
            }
            for (int i = 0; i < nodeCount; i++) { v[i] = rng.nextDouble() + 1e-9; }
            normalise2(v);
            double sig2 = 0.0;
            double[] w = new double[nodeCount];
            for (int it = 0; it < 400; it++) {
                applyA(v, u, nodeCount, rowPtr, src, coeff);
                applyAT(u, w, nodeCount, rowPtr, src, coeff);
                sig2 = norm2(w);
                if (sig2 <= 0) { break; }
                for (int i = 0; i < nodeCount; i++) { v[i] = w[i] / sig2; }
            }
            System.out.println("SPECTRUM rho=" + rho
                    + " sigma_max=" + Math.sqrt(sig2)
                    + " nodes=" + nodeCount + " edges=" + rowPtr[nodeCount]);
        }

        if (Boolean.getBoolean("smx.sf.printBlocks")) {
            java.util.Map<String, Double> block = new java.util.TreeMap<>();
            java.util.Map<String, Integer> count = new java.util.TreeMap<>();
            for (int i = 0; i < nodeCount; i++) {
                String dst = popOf(nodes.get(i), isColumnPair[i]);
                for (int k = rowPtr[i]; k < rowPtr[i + 1]; k++) {
                    String srcPop = popOf(nodes.get(src[k]), isColumnPair[src[k]]);
                    String key = srcPop + "->" + dst;
                    block.merge(key, coeff[k], Double::sum);
                    count.merge(key, 1, Integer::sum);
                }
            }
            StringBuilder sb = new StringBuilder("BLOCKS");
            for (java.util.Map.Entry<String, Double> e : block.entrySet()) {
                sb.append(' ').append(e.getKey()).append('=').append(e.getValue())
                  .append('@').append(count.get(e.getKey()));
            }
            System.out.println(sb);
        }

        if (Boolean.getBoolean("smx.sf.printAlignment")) {
            reportAlignment(nodeCount, rowPtr, src, coeff, sigma_0, isColumnPair);
        }

        // Emit the raw two-step propagation P^2 s_0 as the matcher's own output,
        // so the existing writer and scorer can be pointed at it.
        //
        // The aligned quantity g' P^2 s_0 -- how much of the two-step mass lands
        // on *correct* correspondences -- needs ground truth, which the matcher
        // never sees and should not. Emitting the vector instead moves the
        // labelled half of thecomputation outside, where ground truth already lives, and
        // costs no new machinery: the output is a column-pair matrix like any
        // other and is read by the same code.
        //
        // This is a diagnostic mode, not an arm. The values are not a similarity
        // matrix anyone should match on; they are the second power of the
        // propagation operator applied to the seed, with no normalisation and no
        // seed re-injection.
        if (Boolean.getBoolean("smx.sf.emitTwoStep")) {
            double[] z1 = new double[nodeCount];
            for (int node = 0; node < nodeCount; node++) {
                double sum = 0.0;
                for (int k = rowPtr[node]; k < rowPtr[node + 1]; k++) {
                    sum += coeff[k] * sigma_0[src[k]];
                }
                z1[node] = sum;
            }
            Map<NodePair, Double> twoStep = new HashMap<>(Math.max(16, nodeCount * 2));
            for (int node = 0; node < nodeCount; node++) {
                double sum = 0.0;
                for (int k = rowPtr[node]; k < rowPtr[node + 1]; k++) {
                    sum += coeff[k] * z1[src[k]];
                }
                twoStep.put(nodes.get(node), sum);
            }
            log.info("Emitting raw P^2 s_0 for {} nodes instead of the fixpoint", nodeCount);
            return twoStep;
        }

        if (Boolean.getBoolean("smx.sf.debugGraph")) {
            // Order-independent fingerprints of the graph the loop consumes.
            double[] sortedSigma = sigma_0.clone();
            java.util.Arrays.sort(sortedSigma);
            double[] sortedCoeff = coeff.clone();
            java.util.Arrays.sort(sortedCoeff);
            double sigmaSum = 0.0, coeffSum = 0.0;
            StringBuilder sigmaDigest = new StringBuilder();
            StringBuilder coeffDigest = new StringBuilder();
            for (double v : sortedSigma) {
                sigmaSum += v;
                sigmaDigest.append(Double.doubleToLongBits(v)).append(',');
            }
            for (double v : sortedCoeff) {
                coeffSum += v;
                coeffDigest.append(Double.doubleToLongBits(v)).append(',');
            }
            StringBuilder sigmaSequence = new StringBuilder();
            for (double v : sigma_0) {
                sigmaSequence.append(Double.doubleToLongBits(v)).append(',');
            }
            StringBuilder csr = new StringBuilder();
            for (int v : rowPtr) {
                csr.append(v).append(',');
            }
            csr.append('|');
            for (int v : src) {
                csr.append(v).append(',');
            }
            csr.append('|');
            for (double v : coeff) {
                csr.append(Double.doubleToLongBits(v)).append(',');
            }
            System.out.println("DEBUGGRAPH nodes=" + nodeCount
                    + " sigmaSeq=" + sigmaSequence.toString().hashCode()
                    + " csrSeq=" + csr.toString().hashCode()
                    + " edges=" + coeff.length
                    + " sigmaSum=" + Double.doubleToLongBits(sigmaSum)
                    + " coeffSum=" + Double.doubleToLongBits(coeffSum)
                    + " sigmaSorted=" + sigmaDigest.toString().hashCode()
                    + " coeffSorted=" + coeffDigest.toString().hashCode());
        }

        if (SfExport.ENABLED && !RESTART) {  // off by default (SfExport)
            SfExport.graph(nodes, sigma_0, rowPtr, src, coeff, channelOf, channelNames, SEED_AT, LITERAL_SEED);
        }

        double[] sigma_i = sigma_0.clone();
        double[] sigma_i_plus_1 = new double[nodeCount];

        if (RESTART) {
            if (BUDGET == ChannelBudget.OFF) {
                // Row-normalise the incoming coefficients so every receiver's
                // incoming mass is at most one. Rows already at or below one are
                // left alone: scaling them up would invent mass the graph does not
                // have, and the bound is what the contraction argument needs, not
                // equality.
                for (int node = 0; node < nodeCount; node++) {
                    double rowSum = 0.0;
                    for (int k = rowPtr[node]; k < rowPtr[node + 1]; k++) {
                        rowSum += coeff[k];
                    }
                    if (rowSum > 1.0) {
                        for (int k = rowPtr[node]; k < rowPtr[node + 1]; k++) {
                            coeff[k] /= rowSum;
                        }
                    }
                }
            } else {
                // Item 3: row-normalise WITHIN each channel, so every P_l is
                // row-substochastic on its own, then scale each by its budget.
                int channels = channelNames.size();
                double[] rowSum = new double[channels];
                for (int node = 0; node < nodeCount; node++) {
                    java.util.Arrays.fill(rowSum, 0.0);
                    for (int k = rowPtr[node]; k < rowPtr[node + 1]; k++) {
                        rowSum[channelOf[k]] += coeff[k];
                    }
                    for (int k = rowPtr[node]; k < rowPtr[node + 1]; k++) {
                        double sum = rowSum[channelOf[k]];
                        if (sum > 1.0) {
                            coeff[k] /= sum;
                        }
                    }
                }
                // One share per channel present. NO_FD zeroes the dependency
                // channels and leaves their share unused rather than spreading
                // it over the others.
                double share = channels == 0 ? 0.0 : 1.0 / channels;
                double[] beta = new double[channels];
                for (int c = 0; c < channels; c++) {
                    boolean dependency = channelNames.get(c).startsWith("determines")
                            || channelNames.get(c).startsWith("contains")
                            || channelNames.get(c).startsWith("ucc");
                    beta[c] = (BUDGET == ChannelBudget.NO_FD && dependency) ? 0.0 : share;
                }
                for (int k = 0; k < coeff.length; k++) {
                    coeff[k] *= beta[channelOf[k]];
                }
                log.info("Channel budget {} over {} channels: {}", BUDGET, channels, channelNames);
            }

            if (NON_BACKTRACKING) {
                return nonBacktracking(nodes, nodeCount, sigma_0, rowPtr, src, coeff,
                                       initialMapping);
            }

            while (!convergence && iterationCount <= MAX_ITERATIONS) {
                for (int node = 0; node < nodeCount; node++) {
                    double propagated = 0.0;
                    for (int k = rowPtr[node]; k < rowPtr[node + 1]; k++) {
                        propagated += coeff[k] * sigma_i[src[k]];
                    }
                    sigma_i_plus_1[node] =
                            (1.0 - RESTART_ALPHA) * sigma_0[node] + RESTART_ALPHA * propagated;
                }
                convergence = hasConverged(sigma_i, sigma_i_plus_1, PRECISION_OF_CONVERGENCE);
                System.arraycopy(sigma_i_plus_1, 0, sigma_i, 0, nodeCount);
                iterationCount++;
            }
            log.info("Ran {} Iterations for {} Nodes", iterationCount - 1, initialMapping.size());
            recordRun(iterationCount - 1, convergence);
            Map<NodePair, Double> restarted = new HashMap<>(Math.max(16, nodeCount * 2));
            for (int i = 0; i < nodeCount; i++) {
                restarted.put(nodes.get(i), sigma_i_plus_1[i]);
            }
            return restarted;
        }

        // Trajectory instrumentation. Records the actual recurrence rather than a
        // metric computed from it, because the question -- damped transient or
        // persistent two-cycle -- is about the state sequence:
        //
        //   r1(t) = ||x_{t+1} - x_t||     r2(t) = ||x_{t+1} - x_{t-1}||
        //
        // If both fall to zero the alternation is a transient of a convergent
        // map. If r2 falls while r1 stays bounded away from zero, two distinct
        // limiting states are plausible. Metric flatness cannot distinguish
        // these: a ranking statistic can sit still while the state moves.
        //
        // Also records the argmax that sets the normalisation, because the
        // update is only differentiable where that argmax is unique and stable,
        // and a changing maximiser is itself a candidate source of alternation.
    /**
     * Scale the normalisation divisor: x <- A(q+x) / (alpha * c).
     *
     * This is a DOSE CONTROL on propagation strength, not a harmless rescale.
     * Formula C re-injects q un-normalised every update, so shrinking the state
     * relative to q shifts the balance between injected seed and propagated
     * evidence. At a fixed point of the MAX variant,
     *
     *     x = A(q+x) / (alpha * ||A(q+x)||_inf)   =>   ||x||_inf = 1/alpha,
     *
     * exactly, while ||q||_inf = 1. So alpha sets the seed-to-state ratio
     * analytically: alpha = 4 leaves the state at a quarter of the seed's
     * scale, alpha = 1/2 at twice it. The normaliser's geometry -- which
     * coordinates participate, and the rank-one term they induce -- is
     * untouched, which is what separates this from swapping normaliser.
     */
        final double RELAX = Double.parseDouble(System.getProperty("smx.sf.relax", "1.0"));
        double[] resid_pre = RELAX < 1.0 ? new double[nodeCount] : null;
        final boolean TRAJ = Boolean.getBoolean("smx.sf.printTrajectory");
        double[] sigma_prev = TRAJ ? sigma_i.clone() : null;

        // Mode instrumentation. SF_RELAXATION.md established that the
        // alternation is an eigenmode of the update near lambda = -1; this
        // identifies WHERE that mode lives, without assuming an answer.
        //
        // The successive-difference vectors d_t = x_{t+1} - x_t are power
        // iteration on DF_q restricted to the non-fixed directions, so once a
        // single mode dominates, d_t/||d_t|| converges to its eigenvector and
        //
        //     lambda ~ (d_t . d_{t-1}) / ||d_{t-1}||^2
        //
        // is the Rayleigh estimate of its eigenvalue. cos(d_t, d_{t-1}) near
        // -1 is the evidence that ONE real negative mode dominates; without
        // that, the decomposition below describes a mixture and means little.
        //
        // The candidate carrier is the pendant link between each scored ID pair
        // and its private name pair (SF_PENDANT_EXCHANGE.md). Note that the
        // local 2x2 block of I+P over one pendant has coefficients 1.0 and
        // ~0.5, giving eigenvalues 1 +/- sqrt(0.5) -- both POSITIVE. So the
        // pendant alone does not predict alternation, and this measurement is
        // set up to be able to say so.
        final boolean MODE = Boolean.getBoolean("smx.sf.printMode");
        double[] d_prev = MODE ? new double[nodeCount] : null;
        int[] pendantOf = null;
        if (MODE) {
            java.util.Map<NodePair, Integer> index = new java.util.HashMap<>();
            for (int i = 0; i < nodeCount; i++) { index.put(nodes.get(i), i); }
            pendantOf = new int[nodeCount];
            java.util.Arrays.fill(pendantOf, -1);
            for (int i = 0; i < nodeCount; i++) {
                if (!isScoredPair[i]) { continue; }
                Node a = nodes.get(i).getFirstNode(), b = nodes.get(i).getSecondNode();
                if (a.getNameNode() == null || b.getNameNode() == null) { continue; }
                Integer j = index.get(new NodePair(a.getNameNode(), b.getNameNode()));
                if (j != null) { pendantOf[i] = j; }
            }
            int paired = 0;
            for (int i = 0; i < nodeCount; i++) { if (pendantOf[i] >= 0) { paired++; } }
            System.out.println("PENDANTS scored=" + count(isScoredPair) + " paired=" + paired);
        }

        while (!convergence && iterationCount <= MAX_ITERATIONS) {

            // Double.MIN_VALUE is the smallest POSITIVE double, not the most
            // negative one, so this is only a safe starting point while every
            // sigma stays non-negative. Recorded in SF_NORMALISATION.md; left
            // as it is so the default path is unchanged.
            double maxValueCurrentIteration = Double.MIN_VALUE;
            int argmaxNode = -1;
            double sumOfSquares = 0.0;
            // Per-component maxima, filled in the same pass. Only CWISE uses
            // them; 0.0 is a safe floor while every sigma stays non-negative,
            // and a component that never exceeds it is left undivided below.
            double[] componentMax = null;
            if (NORMALISE == Normalise.CWISE) {
                componentMax = new double[componentCount];
            }

            for (int node = 0; node < nodeCount; node++) {
                double newValue = formula.evaluate(node, sigma_0, sigma_i, rowPtr, src, coeff, isDependencyPair);

                //MaxWert der aktuellen Iteration speichern, damit später normalisieren möglich
                // Which pairs may set the scale. GLOBAL/L2/NONE admit every
                // pair; COLUMN restricts to COLUMN-typed pairs (which includes
                // name pairs); SCORED restricts to the pairs the output is
                // actually read from.
                boolean eligible = (NORMALISE != Normalise.COLUMN || isColumnPair[node])
                        && (NORMALISE != Normalise.SCORED || isScoredPair[node]);
                if (newValue > maxValueCurrentIteration && eligible) {
                    maxValueCurrentIteration = newValue;
                    argmaxNode = node;
                }
                sumOfSquares += newValue * newValue;
                if (componentMax != null && newValue > componentMax[componentOf[node]]) {
                    componentMax[componentOf[node]] = newValue;
                }

                sigma_i_plus_1[node] = newValue;
            }

            // The divisor c actually applied this iteration. Reported as norm=
            // below, so the measured c matches whichever normaliser is in use.
            // An all-zero update never exceeds the sentinel, so argmaxNode stays
            // -1 and maxValueCurrentIteration stays at Double.MIN_VALUE. That
            // sentinel is a positive subnormal: multiplying it by alpha < 1
            // UNDERFLOWS TO ZERO, and the division below then computes 0/0 and
            // writes NaN. Every one of the 30 T2D failures was this, on the 30
            // scenarios whose seed matrix is identically zero (SF_T2D.md). The
            // degenerate state is now detected explicitly and left alone.
            boolean degenerate = (argmaxNode < 0 && sumOfSquares == 0.0);
            double normConstant;
            switch (NORMALISE) {
                case NONE: normConstant = 1.0; break;
                case L2:   normConstant = Math.sqrt(sumOfSquares); break;
                default:   normConstant = maxValueCurrentIteration; break;
            }

            //Normalisieren für die aktuelle Iteration
            double divisor = normConstant * NORM_ALPHA;
            if (NORMALISE == Normalise.CWISE) {
                // Each component by its own maximum. A component whose update is
                // identically zero keeps a divisor of 0 and is left alone, which
                // is the same guard the shared path applies via `degenerate`.
                if (!degenerate) {
                    for (int node = 0; node < nodeCount; node++) {
                        double d = componentMax[componentOf[node]] * NORM_ALPHA;
                        if (d > 0.0) {
                            sigma_i_plus_1[node] = sigma_i_plus_1[node] / d;
                        }
                    }
                }
            } else if (NORMALISE != Normalise.NONE && !degenerate && divisor > 0.0) {
                for (int node = 0; node < nodeCount; node++) {
                    sigma_i_plus_1[node] = sigma_i_plus_1[node] / divisor;
                }
            }

            // Relaxation (SF_TRAJECTORY_REVIEW_20260919, item 2).
            //
            //   x_{t+1} = (1 - eta) x_t + eta F_q(x_t)
            //
            // The relaxed map has the same fixed points as F_q for any eta > 0,
            // and maps each eigenvalue lambda of DF_q to 1 - eta + eta*lambda.
            // A mode near lambda = -1 -- an exact alternation -- goes to 0 at
            // eta = 1/2, while a mode near +1 is barely touched. So if the
            // alternation is a dynamical mode of this operator, eta = 1/2 must
            // remove it; if the alternation survives, it is not that.
            //
            // The residual that decides convergence is deliberately left as the
            // UNRELAXED ||F_q(x) - x||, computed below from resid_pre, so both
            // arms stop on the same criterion and a difference in the answer
            // cannot be an artifact of one arm stopping earlier.
            if (RELAX < 1.0) {
                for (int node = 0; node < nodeCount; node++) {
                    resid_pre[node] = sigma_i_plus_1[node];
                    sigma_i_plus_1[node] = (1.0 - RELAX) * sigma_i[node]
                            + RELAX * sigma_i_plus_1[node];
                }
            }

            if (SfExport.ENABLED) {  // off by default (SfExport); reads only, changes nothing
                double applied = NORMALISE == Normalise.CWISE ? Double.NaN
                        : (NORMALISE == Normalise.NONE || degenerate || !(divisor > 0.0)) ? 1.0 : divisor;
                double res = SimilarityFloodingUtils.calcResidualVector(sigma_i, RELAX < 1.0 ? resid_pre : sigma_i_plus_1);
                SfExport.step(iterationCount + 1, applied, res, res < PRECISION_OF_CONVERGENCE, sigma_i_plus_1);
            }

            if (TRAJ) {
                double r1inf = 0.0, r1two = 0.0, r2inf = 0.0;
                double colSum = 0.0, colMax = 0.0, allSum = 0.0, scoredSum = 0.0;
                for (int n = 0; n < nodeCount; n++) {
                    double d1 = Math.abs(sigma_i_plus_1[n] - sigma_i[n]);
                    double d2 = Math.abs(sigma_i_plus_1[n] - sigma_prev[n]);
                    if (d1 > r1inf) { r1inf = d1; }
                    if (d2 > r2inf) { r2inf = d2; }
                    r1two += d1 * d1;
                    allSum += sigma_i_plus_1[n];
                    if (isColumnPair[n]) {
                        colSum += sigma_i_plus_1[n];
                        if (sigma_i_plus_1[n] > colMax) { colMax = sigma_i_plus_1[n]; }
                    }
                    if (isScoredPair[n]) { scoredSum += sigma_i_plus_1[n]; }
                }
                String amType = argmaxNode < 0 ? "none"
                        : nodes.get(argmaxNode).getFirstNode().getNodeType()
                          + "/" + nodes.get(argmaxNode).getSecondNode().getNodeType();
                System.out.println("TRAJ update=" + (iterationCount + 1)
                        + " r1_inf=" + r1inf + " r1_l2=" + Math.sqrt(r1two)
                        + " r2_inf=" + r2inf
                        + " norm=" + normConstant
                        + " argmax_type=" + amType
                        + " argmax_isCol=" + (argmaxNode >= 0 && isColumnPair[argmaxNode])
                        + " col_sum=" + colSum + " col_max=" + colMax
                        + " scored_sum=" + scoredSum
                        + " argmax_isScored=" + (argmaxNode >= 0 && isScoredPair[argmaxNode])
                        + " all_sum=" + allSum);
                System.arraycopy(sigma_i, 0, sigma_prev, 0, nodeCount);
            }

            if (MODE) {
                // cos(d, x) tests whether the mode is simply "overall scale".
                // A normalisation-induced mode looks like the state itself: the
                // whole vector grows, is divided by a larger maximum, shrinks,
                // is divided by a smaller one. That is a rank-one effect of the
                // -F_q(x) e_k^T term, not a property of the graph.
                double nn = 0.0, np_ = 0.0, dot = 0.0, quad = 0.0, xx = 0.0, dx = 0.0;
                for (int n = 0; n < nodeCount; n++) {
                    double d = sigma_i_plus_1[n] - sigma_i[n];
                    nn += d * d; quad += d * d * d * d;
                    dot += d * d_prev[n]; np_ += d_prev[n] * d_prev[n];
                    xx += sigma_i[n] * sigma_i[n]; dx += d * sigma_i[n];
                }
                double cos = (nn > 0 && np_ > 0) ? dot / Math.sqrt(nn * np_) : Double.NaN;
                double lam = np_ > 0 ? dot / np_ : Double.NaN;
                // Participation ratio: the effective number of components the
                // mode occupies. 1 means a single node carries it.
                double pr = quad > 0 ? (nn * nn) / quad : Double.NaN;
                java.util.Map<String, Double> pop = new java.util.TreeMap<>();
                double pi = 0.0, pj = 0.0, pij = 0.0; int opp = 0, npair = 0;
                for (int n = 0; n < nodeCount; n++) {
                    double d = sigma_i_plus_1[n] - sigma_i[n];
                    pop.merge(popOf(nodes.get(n), isColumnPair[n]), d * d, Double::sum);
                    int j = pendantOf[n];
                    if (j >= 0) {
                        double dj = sigma_i_plus_1[j] - sigma_i[j];
                        pi += d * d; pj += dj * dj; pij += d * dj; npair++;
                        if (d * dj < 0) { opp++; }
                    }
                }
                StringBuilder sb = new StringBuilder("MODE update=" + (iterationCount + 1)
                        + " cos=" + cos + " lambda=" + lam
                        + " norm=" + Math.sqrt(nn) + " partic=" + pr
                        + " cos_dx=" + ((nn > 0 && xx > 0) ? dx / Math.sqrt(nn * xx) : Double.NaN)
                        + " pend_cos=" + ((pi > 0 && pj > 0) ? pij / Math.sqrt(pi * pj) : Double.NaN)
                        + " pend_opp=" + (npair > 0 ? opp / (double) npair : Double.NaN)
                        + " pend_massI=" + (nn > 0 ? pi / nn : Double.NaN)
                        + " pend_massJ=" + (nn > 0 ? pj / nn : Double.NaN));
                for (java.util.Map.Entry<String, Double> e : pop.entrySet()) {
                    if (nn > 0 && e.getValue() / nn > 1e-4) {
                        sb.append(" m:").append(e.getKey()).append('=').append(e.getValue() / nn);
                    }
                }
                System.out.println(sb);
                for (int n = 0; n < nodeCount; n++) { d_prev[n] = sigma_i_plus_1[n] - sigma_i[n]; }
            }

            //Prüfen ob Residuum(sigma_i, sigma_i+1) konvergiert
            convergence = hasConverged(sigma_i,
                    RELAX < 1.0 ? resid_pre : sigma_i_plus_1, PRECISION_OF_CONVERGENCE);
            System.arraycopy(sigma_i_plus_1, 0, sigma_i, 0, nodeCount);
            iterationCount++;
        }

        // Dose report. The update's numerator is A(q+x) = (q+x) + P(q+x), so the
        // propagated share is ||P(q+x)|| / ||q+x||, and the seed's share of the
        // input is ||q|| / ||q+x||. These are the mechanistically meaningful
        // quantities; the converged c is an endogenous consequence, not the
        // explanatory variable (SF_ALPHA.md, reviewer's framing).
        if (Boolean.getBoolean("smx.sf.printDose")) {
            double nq = 0.0, nu = 0.0, np = 0.0, nx = 0.0;
            double sq = 0.0, su = 0.0, sp = 0.0;
            for (int i = 0; i < nodeCount; i++) {
                double u = sigma_0[i] + sigma_i[i];
                double pu = 0.0;
                for (int k = rowPtr[i]; k < rowPtr[i + 1]; k++) {
                    pu += coeff[k] * (sigma_0[src[k]] + sigma_i[src[k]]);
                }
                nq += Math.abs(sigma_0[i]);
                nx += Math.abs(sigma_i[i]);
                nu += Math.abs(u);
                np += Math.abs(pu);
                if (isScoredPair[i]) { sq += Math.abs(sigma_0[i]); su += Math.abs(u); sp += Math.abs(pu); }
            }
            double mq = 0.0, mx = 0.0;
            for (int i = 0; i < nodeCount; i++) {
                mq = Math.max(mq, Math.abs(sigma_0[i]));
                mx = Math.max(mx, Math.abs(sigma_i[i]));
            }
            System.out.println("DOSE prop_share=" + (nu > 0 ? np / nu : Double.NaN)
                    + " seed_share=" + (nu > 0 ? nq / nu : Double.NaN)
                    + " state_over_seed_l1=" + (nq > 0 ? nx / nq : Double.NaN)
                    + " state_over_seed_inf=" + (mq > 0 ? mx / mq : Double.NaN)
                    + " scored_prop_share=" + (su > 0 ? sp / su : Double.NaN)
                    + " scored_seed_share=" + (su > 0 ? sq / su : Double.NaN));
        }

        log.info("Ran {} Iterations for {} Nodes", iterationCount - 1, initialMapping.size());
        recordRun(iterationCount - 1, convergence);
        if (SfExport.ENABLED) {  // off by default (SfExport)
            SfExport.end(iterationCount, convergence);
        }

        // Reporting only (exploratory Q9, hub effect): where the final mass sits. Off by default;
        // changes nothing in the output.
        if (Boolean.getBoolean("smx.sf.massReport")) {
            double total = 0.0, scored = 0.0, table = 0.0, maxScored = 0.0, maxAll = 0.0;
            String maxType = "";
            int nScored = 0;
            for (int i = 0; i < nodeCount; i++) {
                double v = Math.abs(sigma_i_plus_1[i]);
                NodePair np = nodes.get(i);
                total += v;
                if (isScoredPair[i]) { scored += v; nScored++; maxScored = Math.max(maxScored, v); }
                if (np.getFirstNode().getNodeType() == NodeType.TABLE && np.getSecondNode().getNodeType() == NodeType.TABLE) {
                    table += v;
                }
                if (v > maxAll) {
                    maxAll = v;
                    maxType = np.getFirstNode().getNodeType() + "/" + np.getSecondNode().getNodeType()
                            + (np.getFirstNode().isIDNode() ? "/id" : "/literal");
                }
            }
            System.out.println("MASS nodes=" + nodeCount + " scored_pairs=" + nScored
                    + " scored_share=" + (total > 0 ? scored / total : Double.NaN)
                    + " table_share=" + (total > 0 ? table / total : Double.NaN)
                    + " max_scored=" + maxScored + " max_all=" + maxAll + " max_type=" + maxType);
        }

        if (Boolean.getBoolean("smx.sf.debugGraph")) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < nodeCount; i++) {
                out.append(Double.doubleToLongBits(sigma_i_plus_1[i])).append(',');
            }
            System.out.println("DEBUGFIX iterations=" + (iterationCount - 1)
                    + " resultSeq=" + out.toString().hashCode());
        }

        Map<NodePair, Double> result = new HashMap<>(Math.max(16, nodeCount * 2));
        for (int i = 0; i < nodeCount; i++) {
            result.put(nodes.get(i), sigma_i_plus_1[i]);
        }
        return result;
    }

    /**
     * Item 4: the restart update carried on directed edges instead of nodes.
     *
     * m[k] is the message travelling along the edge that CSR entry k records,
     * from src[k] into the node whose row contains k. It is computed at src[k]
     * from src[k]'s own incoming messages *except* the one arriving from the
     * destination, so a value cannot come straight back to where it started.
     * Longer cycles still return, which is the intended difference from simply
     * deleting reverse edges.
     *
     * Each node's full incoming sum is computed once per iteration and the
     * returning term subtracted, so the cost stays linear in the edge count
     * rather than quadratic in degree.
     */
    /**
     * How the graph sits relative to the evidence, rather than how it is shaped.
     *
     * Every graph statistic measured so far -- edge count, both degree
     * marginals, arity, the two-walk count W = sum_v d_in(v) d_out(v) --
     * describes the dependency graph in isolation. Similarity Flooding does not
     * consume a graph; it consumes P applied to an initial similarity. Two
     * graphs with identical W and identical marginals can differ entirely in
     * whether their two-step routes begin at candidate pairs that already score
     * well and end at plausible ones.
     *
     * So this reports the seed-weighted analogues of what those unweighted
     * statistics stand in for:
     *
     *   mass1    = 1' P s_0     total mass after one raw propagation step
     *   mass2    = 1' P^2 s_0   after two -- the depth at which the
     *                           degree-sequence effect appears in full
     *   w_seeded = sum_v (P s_0)[v] * outmass(v)
     *                           the seed-weighted two-walk quantity. W counts
     *                           routes through v; this weights each by how much
     *                           similarity actually arrives there.
     *
     * and a concentration sum(y^2)/(sum y)^2 for each, which is 1/k when the
     * mass is spread evenly over k nodes. A graph that funnels its mass into a
     * few hubs and one that spreads it can share a W and differ here.
     *
     * P is the raw propagation operator from the CSR -- no per-iteration
     * normalisation and no seed re-injection -- so these are properties of
     * (graph, seed) rather than of the fixpoint formula in use. Column-pair
     * restricted versions are reported alongside, because the scored output
     * lives on those and mass landing on auxiliary pairs cannot help.
     *
     * Ground truth is not available inside the matcher, so g' P^2 s_0 -- the
     * mass reaching *correct* correspondences -- is not computed here. These are
     * its unlabelled precursors.
     */
    /**
     * Which population a node pair belongs to, for the block report.
     *
     * COLUMN-typed pairs split into the scored ID pairs and the name pairs that
     * carry the seed under NAME attachment, because that is the distinction the
     * near-bipartite explanation turns on.
     */
    /** y = A x, with A = I + P and P held as CSR over incoming edges. */
    private static void applyA(double[] x, double[] y, int n,
                               int[] rowPtr, int[] src, double[] coeff) {
        for (int i = 0; i < n; i++) {
            double acc = x[i];
            for (int k = rowPtr[i]; k < rowPtr[i + 1]; k++) {
                acc += coeff[k] * x[src[k]];
            }
            y[i] = acc;
        }
    }

    /** y = A^T x. The transpose scatters where applyA gathers. */
    private static void applyAT(double[] x, double[] y, int n,
                                int[] rowPtr, int[] src, double[] coeff) {
        System.arraycopy(x, 0, y, 0, n);
        for (int i = 0; i < n; i++) {
            double xi = x[i];
            if (xi == 0.0) { continue; }
            for (int k = rowPtr[i]; k < rowPtr[i + 1]; k++) {
                y[src[k]] += coeff[k] * xi;
            }
        }
    }

    private static double norm2(double[] v) {
        double s = 0.0;
        for (double x : v) { s += x * x; }
        return Math.sqrt(s);
    }

    private static void normalise2(double[] v) {
        double n = norm2(v);
        if (n > 0) { for (int i = 0; i < v.length; i++) { v[i] /= n; } }
    }

    private static int count(boolean[] b) {
        int n = 0;
        for (boolean x : b) { if (x) { n++; } }
        return n;
    }

    private static String popOf(NodePair np, boolean isCol) {
        Node a = np.getFirstNode(), b = np.getSecondNode();
        boolean bothCol = a.getNodeType() == NodeType.COLUMN
                && b.getNodeType() == NodeType.COLUMN;
        if (bothCol) {
            // The distinction that matters is ID node vs name node, NOT NodeType.
            // filterMapping reads the answer off pairs where BOTH nodes are ID
            // nodes; buildInitialMappingFromMatrix keys the seed by name-node
            // pairs. isColumnPair does not separate these -- it tests NodeType
            // only -- so it cannot be used to tell the seeded population from
            // the scored one.
            if (a.isIDNode() && b.isIDNode()) { return "COLID"; }
            if (!a.isIDNode() && !b.isIDNode()) { return "COLNAME"; }
            return "COLMIXED";
        }
        return a.getNodeType() + "/" + b.getNodeType();
    }

    private static void reportAlignment(int nodeCount, int[] rowPtr, int[] src,
                                        double[] coeff, double[] sigma_0,
                                        boolean[] isColumnPair) {
        double[] y1 = new double[nodeCount];
        for (int node = 0; node < nodeCount; node++) {
            double sum = 0.0;
            for (int k = rowPtr[node]; k < rowPtr[node + 1]; k++) {
                sum += coeff[k] * sigma_0[src[k]];
            }
            y1[node] = sum;
        }
        double[] y2 = new double[nodeCount];
        for (int node = 0; node < nodeCount; node++) {
            double sum = 0.0;
            for (int k = rowPtr[node]; k < rowPtr[node + 1]; k++) {
                sum += coeff[k] * y1[src[k]];
            }
            y2[node] = sum;
        }
        double[] outMass = new double[nodeCount];
        for (int node = 0; node < nodeCount; node++) {
            for (int k = rowPtr[node]; k < rowPtr[node + 1]; k++) {
                outMass[src[k]] += coeff[k];
            }
        }
        double m0 = 0, m1 = 0, m2 = 0, wSeeded = 0;
        double m0c = 0, m1c = 0, m2c = 0;
        double sq1 = 0, sq2 = 0, sq1c = 0, sq2c = 0;
        int cols = 0;
        for (int node = 0; node < nodeCount; node++) {
            m0 += sigma_0[node]; m1 += y1[node]; m2 += y2[node];
            sq1 += y1[node] * y1[node]; sq2 += y2[node] * y2[node];
            wSeeded += y1[node] * outMass[node];
            if (isColumnPair[node]) {
                cols++;
                m0c += sigma_0[node]; m1c += y1[node]; m2c += y2[node];
                sq1c += y1[node] * y1[node]; sq2c += y2[node] * y2[node];
            }
        }
        System.out.println("ALIGNMENT nodes=" + nodeCount + " colpairs=" + cols
                + " mass0=" + m0 + " mass1=" + m1 + " mass2=" + m2
                + " w_seeded=" + wSeeded
                + " conc1=" + (m1 > 0 ? sq1 / (m1 * m1) : 0.0)
                + " conc2=" + (m2 > 0 ? sq2 / (m2 * m2) : 0.0)
                + " mass0_col=" + m0c + " mass1_col=" + m1c + " mass2_col=" + m2c
                + " conc1_col=" + (m1c > 0 ? sq1c / (m1c * m1c) : 0.0)
                + " conc2_col=" + (m2c > 0 ? sq2c / (m2c * m2c) : 0.0));
    }

    private static Map<NodePair, Double> nonBacktracking(
            List<NodePair> nodes, int nodeCount, double[] sigma_0,
            int[] rowPtr, int[] src, double[] coeff,
            Map<NodePair, Double> initialMapping) {

        int edgeCount = coeff.length;
        // opposite[k] is the entry carrying the message back along the same
        // pair of nodes, or -1 when the graph holds only one direction.
        int[] opposite = new int[edgeCount];
        java.util.Arrays.fill(opposite, -1);
        Map<Long, Integer> byEndpoints = new HashMap<>(Math.max(16, edgeCount * 2));
        for (int node = 0; node < nodeCount; node++) {
            for (int k = rowPtr[node]; k < rowPtr[node + 1]; k++) {
                byEndpoints.put(((long) src[k] << 32) | node, k);
            }
        }
        for (int node = 0; node < nodeCount; node++) {
            for (int k = rowPtr[node]; k < rowPtr[node + 1]; k++) {
                Integer back = byEndpoints.get(((long) node << 32) | src[k]);
                if (back != null) {
                    opposite[k] = back;
                }
            }
        }

        double[] message = new double[edgeCount];
        double[] next = new double[edgeCount];
        double[] incoming = new double[nodeCount];
        double[] x = sigma_0.clone();
        double[] previous = new double[nodeCount];
        boolean convergence = false;
        int iterationCount = 0;

        while (!convergence && iterationCount <= MAX_ITERATIONS) {
            for (int node = 0; node < nodeCount; node++) {
                double sum = 0.0;
                for (int k = rowPtr[node]; k < rowPtr[node + 1]; k++) {
                    sum += coeff[k] * message[k];
                }
                incoming[node] = sum;
            }
            for (int node = 0; node < nodeCount; node++) {
                for (int k = rowPtr[node]; k < rowPtr[node + 1]; k++) {
                    int from = src[k];
                    double returning = opposite[k] < 0 ? 0.0
                            : coeff[opposite[k]] * message[opposite[k]];
                    next[k] = (1.0 - RESTART_ALPHA) * sigma_0[from]
                            + RESTART_ALPHA * (incoming[from] - returning);
                }
            }
            System.arraycopy(next, 0, message, 0, edgeCount);
            System.arraycopy(x, 0, previous, 0, nodeCount);
            for (int node = 0; node < nodeCount; node++) {
                double sum = 0.0;
                for (int k = rowPtr[node]; k < rowPtr[node + 1]; k++) {
                    sum += coeff[k] * message[k];
                }
                x[node] = (1.0 - RESTART_ALPHA) * sigma_0[node] + RESTART_ALPHA * sum;
            }
            convergence = hasConverged(previous, x, PRECISION_OF_CONVERGENCE);
            iterationCount++;
        }

        log.info("Ran {} Iterations for {} Nodes", iterationCount - 1, initialMapping.size());
        recordRun(iterationCount - 1, convergence);
        Map<NodePair, Double> result = new HashMap<>(Math.max(16, nodeCount * 2));
        for (int i = 0; i < nodeCount; i++) {
            result.put(nodes.get(i), x[i]);
        }
        return result;
    }

    /**
     * The seed value for one propagation vertex, under the chosen attachment.
     *
     * The supplied matrix is keyed by column *name* pairs. A column ID pair
     * reaches its own seed entry through the name node it was built with, which
     * is the same route {@code filterMapping} uses to read the answer back out.
     */
    private static double seedOf(NodePair node, Map<NodePair, Double> initialMapping) {
        if (SEED_AT == SeedAttachment.NAME) {
            return initialMapping.getOrDefault(node, 0.0);
        }
        Node first = node.getFirstNode();
        Node second = node.getSecondNode();
        // With literal seeds, attachment moves only the column seed: other literal pairs (table,
        // type and cross-kind name pairs) keep their string similarity under ID and SPLIT too.
        if ("PREFIX_SUFFIX".equals(LITERAL_SEED) && !first.isIDNode() && !second.isIDNode()
                && !(isColumnName(first) && isColumnName(second))) {
            return initialMapping.getOrDefault(node, 0.0);
        }
        boolean columnIdPair = first.isIDNode() && second.isIDNode()
                && first.getNodeType().equals(NodeType.COLUMN)
                && second.getNodeType().equals(NodeType.COLUMN)
                && first.getNameNode() != null && second.getNameNode() != null;
        if (columnIdPair) {
            double atId = initialMapping.getOrDefault(
                    new NodePair(first.getNameNode(), second.getNameNode()), 0.0);
            return SEED_AT == SeedAttachment.ID ? atId : 0.5 * atId;
        }
        double atName = initialMapping.getOrDefault(node, 0.0);
        return SEED_AT == SeedAttachment.ID ? 0.0 : 0.5 * atName;
    }

    private static boolean isColumnName(Node n) {
        return !n.isIDNode() && n.getNodeType().equals(NodeType.COLUMN) && n.getRepresentedColumn() != null;
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

        if (ColumnRelationFlooding.supports(graphEncoding)) {
            if (PAIR_SCOPE != PairScope.NONE) {
                throw new IllegalArgumentException("smx.sf.pairScope applies to schema-graph encodings only, not "
                        + graphEncoding);
            }
            return ColumnRelationFlooding.match(sourceDb, targetDb, prevMatrix,
                    graphEncoding, policy, formula, fdFilterThreshold,
                    constraintWeighting, dependencyTopK, fdAllowListFile,
                    relationalNullMaskDir);
        }

        long sfStart = System.nanoTime();
        SchemaGraphBuilder schemaGraphBuilder = new SchemaGraphBuilder(this.uccFilterThreshold, this.indFilterThreshold, this.fdFilterThreshold, this.constraintWeighting, this.graphEncoding, this.dependencyTopK, this.fdAllowListFile);

        Graph<Node, LabelEdge> sourceGraph = schemaGraphBuilder.transformIntoGraphRepresentationSchema(sourceDb);
        Graph<Node, LabelEdge> targetGraph = schemaGraphBuilder.transformIntoGraphRepresentationSchema(targetDb);

        //Combine both Graphs into a connectivity-graph
        Graph<NodePair, LabelEdge> connectivityGraph = createConnectivityGraph(sourceGraph, targetGraph);
        if (PAIR_SCOPE != PairScope.NONE) {
            int[] kept = new int[2];
            connectivityGraph = pruneColumnPairs(connectivityGraph, prevMatrix, sourceDb, targetDb, kept);
            System.out.println("PAIRSCOPE\t" + matchTask.getDataset().getName() + "\t" + matchTask.getScenario().getName()
                    + "\t" + PAIR_SCOPE + "\t" + PAIR_SCOPE_PARAM + "\t" + kept[0] + "\t" + kept[1]);
        }

        //Transform the connectivity-graph into the propagation-graph on which the algorithm executes
        Graph<NodePair, CoefficientEdge> propagationGraph = inducePropagationGraph(connectivityGraph, sourceGraph, targetGraph, policy);

        //Calculate the initial mapping (similarity) values
        Map<NodePair, Double> initialMapping = buildInitialMappingFromMatrix(prevMatrix,sourceDb, targetDb);
        java.util.Set<NodePair> exportMatrixKeys = SfExport.ENABLED ? new java.util.HashSet<>(initialMapping.keySet()) : null;
        if ("PREFIX_SUFFIX".equals(LITERAL_SEED)) {
            addLiteralSeeds(propagationGraph, initialMapping);
        }
        if (SfExport.ENABLED) {  // off by default: -Dsmx.sf.export=<dir> (SfExport)
            java.util.Set<NodePair> literalKeys = new java.util.HashSet<>(initialMapping.keySet());
            literalKeys.removeAll(exportMatrixKeys);
            java.util.Map<String, String> s = new java.util.LinkedHashMap<>();
            s.put("dataset", matchTask.getDataset().getName());
            s.put("scenario", matchTask.getScenario().getName());
            s.put("fixpoint", fixpoint);
            s.put("propCoeffPolicy", propCoeffPolicy);
            s.put("graphEncoding", graphEncoding);
            s.put("seedAttachment", SEED_AT.toString());
            s.put("literalSeed", LITERAL_SEED);
            s.put("epsilon", Double.toString(PRECISION_OF_CONVERGENCE));
            s.put("maxIterations", Integer.toString(MAX_ITERATIONS));
            s.put("normalise", NORMALISE.toString());
            s.put("normaliseAlpha", Double.toString(NORM_ALPHA));
            s.put("relax", System.getProperty("smx.sf.relax", "1.0"));
            s.put("restart", Boolean.toString(RESTART));
            s.put("pairScope", PAIR_SCOPE.toString());
            s.put("unpruned", Boolean.toString(PAIR_SCOPE == PairScope.NONE));
            s.put("initialMatrix", initialMatrix);
            s.put("edge_semantics", "incoming CSR: u_dst += coefficient * (sigma0 + x)[src] (formula C, Value = SIGMA_0_PLUS_I)");
            s.put("update_rule", "formula " + fixpoint + ": FORMULA_C u = sigma0 + x_t + P(sigma0 + x_t); x_{t+1} = u / M_t;"
                    + " M_t = max over all nodes of u (GLOBAL) times normaliseAlpha; no division if u == 0");
            s.put("stop_rule", "stop when ||x_{t+1} - x_t||_2 < epsilon or after maxIterations + 1 updates");
            s.put("output_link", "MatchingStepLine2[row][col] = (float) x_final on nodes.tsv rows with scored = true"
                    + " (both column ID nodes; filterMapping keys them by their name nodes; no other selection)");
            SfExport.begin(matchTask.getDataset().getName(), matchTask.getScenario().getName(), s, exportMatrixKeys,
                    literalKeys, PAIR_SCOPE != PairScope.NONE);
        }

        //Run the similarity-flooding algorithm
        long sfBuilt = System.nanoTime();
        LAST_RUN.set(new int[]{-1, -1});
        Map<NodePair, Double> floodingResults = similarityFlooding(propagationGraph, initialMapping, formula);
        long sfDone = System.nanoTime();
        int[] run = LAST_RUN.get();
        // One machine-readable line per scenario; remote_runner.py keeps these in sf_stats.tsv.
        System.out.println("SFSTATS	" + matchTask.getDataset().getName() + "	" + matchTask.getScenario().getName()
                + "	" + run[0] + "	" + run[1]
                + "	" + (sourceGraph.vertexSet().size() + targetGraph.vertexSet().size())
                + "	" + (sourceGraph.edgeSet().size() + targetGraph.edgeSet().size())
                + "	" + propagationGraph.vertexSet().size() + "	" + propagationGraph.edgeSet().size()
                + "	" + (sfBuilt - sfStart) / 1_000_000 + "	" + (sfDone - sfBuilt) / 1_000_000);

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

        // Index by both the full "<table>.<column>" cell and the bare label. A
        // column label may itself contain a dot, in which case stripping at the
        // last dot loses part of the name and the cell silently seeds zero;
        // stripping at the first is no better, because table names here are file
        // names like "source.csv". The qualified name is tried first.
        String[] header = lines.get(0).split(",", -1);
        Map<String, Integer> columnOf = new HashMap<>();
        for (int j = 1; j < header.length; j++) {
            columnOf.putIfAbsent(normaliseLabel(header[j]), j - 1);
            columnOf.put(stripQuotes(header[j]), j - 1);
        }
        Map<String, Integer> rowOf = new HashMap<>();
        List<String[]> body = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] cells = lines.get(i).split(",", -1);
            if (cells.length < 2) {
                continue;
            }
            rowOf.putIfAbsent(normaliseLabel(cells[0]), body.size());
            rowOf.put(stripQuotes(cells[0]), body.size());
            body.add(cells);
        }

        List<String> sourceLabels = labelsOf(sourceDb);
        List<String> targetLabels = labelsOf(targetDb);
        List<String> sourceQualified = qualifiedLabelsOf(sourceDb);
        List<String> targetQualified = qualifiedLabelsOf(targetDb);
        float[][] out = new float[sourceLabels.size()][targetLabels.size()];
        int resolved = 0;
        for (int i = 0; i < sourceLabels.size(); i++) {
            Integer r = rowOf.get(sourceQualified.get(i));
            if (r == null) {
                r = rowOf.get(sourceLabels.get(i));
            }
            if (r == null) {
                continue;
            }
            String[] cells = body.get(r);
            for (int j = 0; j < targetLabels.size(); j++) {
                Integer c = columnOf.get(targetQualified.get(j));
                if (c == null) {
                    c = columnOf.get(targetLabels.get(j));
                }
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

    private static String stripQuotes(String raw) {
        String label = raw.trim();
        if (label.startsWith("\"") && label.endsWith("\"") && label.length() > 1) {
            label = label.substring(1, label.length() - 1);
        }
        return label;
    }

    private static String normaliseLabel(String raw) {
        String label = stripQuotes(raw);
        int dot = label.lastIndexOf('.');
        return dot >= 0 ? label.substring(dot + 1) : label;
    }

    /** "&lt;table&gt;.&lt;column&gt;", the form saveOutputVerbose writes. */
    private static List<String> qualifiedLabelsOf(Database db) {
        List<String> out = new ArrayList<>();
        for (Table table : db.getTables()) {
            for (Column column : table.getColumns()) {
                out.add(table.getName() + "." + column.getLabel());
            }
        }
        return out;
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

    /** Melnik et al.'s StringMatch seed on every pair of non-ID nodes not seeded by the matrix. */
    private static void addLiteralSeeds(Graph<NodePair, CoefficientEdge> propagationGraph,
                                        Map<NodePair, Double> initialMapping) {
        de.uni_marburg.schematch.similarity.string.PrefixSuffix stringMatch =
                new de.uni_marburg.schematch.similarity.string.PrefixSuffix();
        for (NodePair pair : propagationGraph.vertexSet()) {
            Node a = pair.getFirstNode();
            Node b = pair.getSecondNode();
            if (a.isIDNode() || b.isIDNode() || initialMapping.containsKey(pair)) {
                continue;
            }
            initialMapping.put(pair, (double) stringMatch.compare(a.getValue(), b.getValue()));
        }
    }

    /** R8: the kept cells of q under the pair scope (see PAIR_SCOPE). */
    static boolean[][] pairScopeKeep(float[][] q, PairScope scope, double param) {
        int n = q.length, m = n == 0 ? 0 : q[0].length;
        boolean[][] keep = new boolean[n][m];
        if (scope == PairScope.QUANTILE) {
            float[] all = new float[n * m];
            for (int i = 0; i < n; i++) {
                System.arraycopy(q[i], 0, all, i * m, m);
            }
            Arrays.sort(all);
            float threshold = all.length == 0 ? 0f : all[Math.min(all.length - 1, (int) Math.floor(param * all.length))];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < m; j++) {
                    keep[i][j] = q[i][j] >= threshold;
                }
            }
        } else if (scope == PairScope.GLOBALTOPK) {
            float[] all = new float[n * m];
            for (int i = 0; i < n; i++) {
                System.arraycopy(q[i], 0, all, i * m, m);
            }
            Arrays.sort(all);
            int count = (int) Math.min(all.length, Math.ceil(param * Math.max(n, m)));
            float threshold = all.length == 0 || count == 0 ? Float.POSITIVE_INFINITY : all[all.length - count];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < m; j++) {
                    keep[i][j] = q[i][j] >= threshold;
                }
            }
        } else if (scope == PairScope.ROWREL) {
            for (int i = 0; i < n; i++) {
                float max = Float.NEGATIVE_INFINITY;
                for (int j = 0; j < m; j++) {
                    max = Math.max(max, q[i][j]);
                }
                for (int j = 0; j < m; j++) {
                    keep[i][j] = q[i][j] >= param * max;
                }
            }
        } else if (scope == PairScope.ROWTOPK || scope == PairScope.COLTOPK || scope == PairScope.MUTUALTOPK) {
            int k = (int) param;
            float[] rowCut = new float[n];
            float[] colCut = new float[m];
            for (int i = 0; i < n; i++) {
                float[] row = q[i].clone();
                Arrays.sort(row);
                rowCut[i] = row.length <= k ? Float.NEGATIVE_INFINITY : row[row.length - k];
            }
            for (int j = 0; j < m; j++) {
                float[] col = new float[n];
                for (int i = 0; i < n; i++) {
                    col[i] = q[i][j];
                }
                Arrays.sort(col);
                colCut[j] = col.length <= k ? Float.NEGATIVE_INFINITY : col[col.length - k];
            }
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < m; j++) {
                    boolean inRow = q[i][j] >= rowCut[i];
                    boolean inCol = q[i][j] >= colCut[j];
                    keep[i][j] = scope == PairScope.ROWTOPK ? inRow
                            : scope == PairScope.COLTOPK ? inCol : inRow && inCol;
                }
            }
        } else {
            int k = (int) param;
            float[] rowCut = new float[n];
            float[] colCut = new float[m];
            for (int i = 0; i < n; i++) {
                float[] row = q[i].clone();
                Arrays.sort(row);
                rowCut[i] = row.length <= k ? Float.NEGATIVE_INFINITY : row[row.length - k];
            }
            for (int j = 0; j < m; j++) {
                float[] col = new float[n];
                for (int i = 0; i < n; i++) {
                    col[i] = q[i][j];
                }
                Arrays.sort(col);
                colCut[j] = col.length <= k ? Float.NEGATIVE_INFINITY : col[col.length - k];
            }
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < m; j++) {
                    keep[i][j] = q[i][j] >= rowCut[i] || q[i][j] >= colCut[j];
                }
            }
        }
        return keep;
    }

    /**
     * R8: the connectivity graph without the column pairs the pair scope does not keep.
     *
     * The graph is rebuilt from the original edges, in their original order, skipping every edge with
     * an endpoint that is a pruned column pair (a column-ID pair or its name pair). Vertices are not
     * removed from the original graph: NodePair's equality is order-insensitive while its hash is not
     * (see NodePair), so an edge may hold an equal twin of a vertex, and removing the vertex would
     * leave such an edge dangling. Each endpoint is judged by its own column names instead. As in
     * Melnik's pairwise connectivity graph, a pair that keeps no edge pair is not in the result.
     * Writes {kept, total} column pairs into {@code counts}.
     */
    private static Graph<NodePair, LabelEdge> pruneColumnPairs(Graph<NodePair, LabelEdge> connectivityGraph,
                                                               float[][] q, Database sourceDb, Database targetDb,
                                                               int[] counts) {
        boolean[][] keep = pairScopeKeep(q, PAIR_SCOPE, PAIR_SCOPE_PARAM);
        Map<NodePair, Boolean> columnPairs = new HashMap<>();
        for (Table sourceTable : sourceDb.getTables()) {
            for (Table targetTable : targetDb.getTables()) {
                List<Column> sourceColumns = sourceTable.getColumns();
                List<Column> targetColumns = targetTable.getColumns();
                for (int i = 0; i < sourceColumns.size(); i++) {
                    Node s = new Node(sourceColumns.get(i).getLabel(), NodeType.COLUMN, null, false, null, sourceTable, null);
                    for (int j = 0; j < targetColumns.size(); j++) {
                        Node t = new Node(targetColumns.get(j).getLabel(), NodeType.COLUMN, null, false, null, targetTable, null);
                        columnPairs.put(new NodePair(s, t), keep[sourceTable.getOffset() + i][targetTable.getOffset() + j]);
                    }
                }
            }
        }
        Graph<NodePair, LabelEdge> pruned = EdgeCollisionAudit.newGraph(LabelEdge.class, "connectivity");
        int dropped = 0;
        for (LabelEdge edge : connectivityGraph.edgeSet()) {
            NodePair source = connectivityGraph.getEdgeSource(edge);
            NodePair target = connectivityGraph.getEdgeTarget(edge);
            if (isPruned(source, columnPairs) || isPruned(target, columnPairs)) {
                dropped++;
                continue;
            }
            pruned.addVertex(source);
            pruned.addVertex(target);
            pruned.addEdge(source, target, new LabelEdge(edge.getLabel(), edge.getWeight()));
        }
        counts[1] = columnPairs.size();
        counts[0] = (int) columnPairs.values().stream().filter(Boolean::booleanValue).count();
        log.info("Pair scope {} {}: kept {} of {} column pairs; pair graph {} -> {} vertices, {} edges dropped",
                PAIR_SCOPE, PAIR_SCOPE_PARAM, counts[0], counts[1], connectivityGraph.vertexSet().size(),
                pruned.vertexSet().size(), dropped);
        return pruned;
    }

    /** R8: whether a pair-graph vertex is a column-ID pair or column name pair that the scope prunes. */
    private static boolean isPruned(NodePair pair, Map<NodePair, Boolean> columnPairs) {
        Node a = pair.getFirstNode();
        Node b = pair.getSecondNode();
        if (!NodeType.COLUMN.equals(a.getNodeType()) || !NodeType.COLUMN.equals(b.getNodeType())) {
            return false;
        }
        NodePair key;
        if (a.isIDNode() && b.isIDNode()) {
            if (a.getNameNode() == null || b.getNameNode() == null) {
                return false;
            }
            key = new NodePair(a.getNameNode(), b.getNameNode());
        } else if (!a.isIDNode() && !b.isIDNode()) {
            key = pair;
        } else {
            return false;
        }
        Boolean kept = columnPairs.get(key);
        return kept != null && !kept;
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
        CONSTRAINT_WEIGHTING("constraintWeighting", List.of("NONE", "QUALITY", "QUALITY_RANK", "CONSTANT", "TOPK_HYBRID", "GATED")),
        GRAPH_ENCODING("graphEncoding", List.of("COMPLETE", "QUICK", "COLUMN_ONLY", "RELATIONAL", "RELATIONAL_IND",
                "RELATIONAL_FD_IND", "RELATIONAL_FD_IND_U", "RELATIONAL_FD_IND_U_GATE",
                "RELATIONAL_FD_JACCARD", "RELATIONAL_FD_JACCARD_U_GATE")),
        DEPENDENCY_TOP_K("dependencyTopK", List.of("positiveIntegerOrEmpty")),
        FD_ALLOW_LIST_FILE("fdAllowListFile", List.of("fileNameOrEmpty")),
        INITIAL_MATRIX("initialMatrix", List.of("PREVIOUS", "RAW_FIRST_LINE", "FILE")),
        INITIAL_MATRIX_DIR("initialMatrixDir", List.of("directoryOrEmpty")),
        RELATIONAL_NULL_MASK_DIR("relationalNullMaskDir", List.of("directoryOrEmpty"));

        public final String key;
        public final Collection<String> possibleValues;

        Param(String key, Collection<String> possibleValues) {
            this.key = key;
            this.possibleValues = possibleValues;
        }
    }

}
