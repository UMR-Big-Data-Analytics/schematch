package de.uni_marburg.schematch.matching.similarityFlooding;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Database;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.data.metadata.dependency.FunctionalDependency;
import de.uni_marburg.schematch.data.metadata.dependency.InclusionDependency;
import de.uni_marburg.schematch.data.metadata.dependency.UniqueColumnCombination;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.graph.DirectedWeightedPseudograph;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

class SchemaGraphBuilder {
    private static final Logger log = LogManager.getLogger(SchemaGraphBuilder.class);
    private final DependencyFilter dependencyFilter;
    private final boolean qualityWeightedConstraints;
    /**
     * Cupid's reinforcement rule, transplanted onto dependency edges.
     *
     * QUALITY damps and only damps: the coefficient is multiplied by the
     * dependency's quality score in [0,1], so a middling dependency is always
     * weaker than an ordinary structural edge and a strong one is at best
     * equal to it. Cupid (Madhavan et al., VLDB'01) does something the thesis
     * never tried: its TreeMatch *increases* the structural similarity of the
     * leaves under a pair of nodes by c_inc when their weighted similarity
     * exceeds th_high, *decreases* it by c_dec below th_low, and leaves it
     * alone between the two.
     *
     * GATED is that rule over dependency confidence: three bands with a dead
     * zone, rather than one continuous multiplier.
     *
     * The band *ratios* are Cupid's (c_inc 1.2 : neutral 1.0 : c_dec 0.9), but
     * they are anchored so the top band sits at 1.0, because LabelEdge holds a
     * weight to [0,1] and a structural edge already weighs 1.0 -- there is no
     * headroom above it, and widening that contract would also change the
     * RELATIONAL encoding, which reads the same weights into its relation
     * matrices. So the effective bands are {1.0, 1/1.2, 0.9/1.2}.
     *
     * That anchoring is not a compromise, it is the sharper experiment. What
     * QUALITY actually does wrong is damp *everything*: gpdep is seldom near
     * 1, so every dependency edge propagates more weakly than an ordinary
     * structural edge, however genuine it is. GATED says instead: a dependency
     * good enough to clear th_high propagates at full structural strength, a
     * middling one is modestly damped, a poor one is damped hard. The contrast
     * under test is therefore "trust confidently above a bar" against "always
     * discount in proportion to the score".
     *
     * Thresholds default to Cupid's (th_high 0.6, th_low 0.35). They transfer
     * in scale, since gpdep/PK/FK scores are also in [0,1], but NOT in
     * meaning: Cupid thresholds a similarity between two schema elements, this
     * thresholds the genuineness of one dependency. Overridable per run via
     * -Dsmx.sf.gate.* .
     */
    private final boolean gatedConstraints;
    private final double gateHigh;
    private final double gateLow;
    private final double gateIncrease;
    private final double gateDecrease;
    private final String graphEncoding;
    private final String fdAllowListFile;
    /**
     * QUALITY_RANK: the diagnosis in the QUALITY comment above, treated.
     *
     * QUALITY damps every dependency edge because gpdep is seldom near 1, so
     * even the best dependency in a schema propagates more weakly than an
     * ordinary structural edge. That is a calibration problem, not evidence
     * that dependency confidence is useless. QUALITY_RANK replaces the raw
     * score with its percentile within the dependencies of the same type in the
     * same schema: the strongest dependency present weighs 1.0, the weakest
     * weighs near 0, and the shape of the gpdep distribution drops out.
     *
     * It is deliberately scale-free. Two schemas whose dependencies are all
     * mediocre and all excellent respectively produce the same weights, which is
     * the point: SF only ever compares dependency edges against other edges in
     * the same graph, so only the ordering within a graph can matter to it.
     *
     * The distribution is set per dependency type immediately before the edges
     * are built, and is over the *selected* dependencies -- what survives the
     * filter -- not the discovered pool.
     */
    private final boolean rankWeightedConstraints;
    /**
     * CONSTANT: the control for QUALITY, per FINDINGS 97.
     *
     * The four weighting arms measured there order exactly as their damping
     * strength does and not as their discrimination does, which suggests the
     * benefit of QUALITY is that it makes every dependency edge weaker than an
     * ordinary structural edge -- not that it tells strong dependencies from
     * weak ones. CONSTANT tests that directly: every dependency edge gets the
     * same multiplier, and no score is consulted at all.
     *
     * If it reproduces QUALITY, dependency confidence contributes nothing to SF
     * beyond announcing that dependencies are weak, and constraintWeighting
     * collapses to one number. Set with -Dsmx.sf.constantWeight.
     */
    private final boolean constantWeightedConstraints;
    private final double constantWeight;
    /**
     * TOPK_HYBRID: selection without the removal penalty (SF_TOPK_HYBRID.md).
     *
     * Every selection experiment so far deleted what it did not select, and
     * every damping experiment treated all dependencies alike. This gives the
     * top k dependencies of each dependant column weight topkHigh and the rest
     * topkLow, so selection can be tested at a matched weight or as a pure
     * loudness difference with nothing removed.
     *
     * Per attribute rather than globally: NGPDEP is a share within a dependant
     * column, so a global cut selects columns with few competing determinants
     * rather than good dependencies.
     */
    private final boolean topkHybridConstraints;
    private final int topK;
    /**
     * Promote this percentage of each dependant column's dependencies instead of
     * a fixed count, with a floor of one. Zero, the default, keeps the fixed k.
     */
    private final double topKPercent;
    private final double topkHigh;
    private final double topkLow;
    private java.util.Set<FunctionalDependency> promoted = null;
    // A blank threshold means that dependency type is not configured for this
    // run, and DependencyFilter throws on parsing it. The per-type graph methods
    // never reach it; the rank preparation would, so it checks first.
    private final String uccThresholdRaw;
    private final String indThresholdRaw;
    private double[] fdRankScores = null;
    private double[] uccRankScores = null;
    private double[] indRankScores = null;

    SchemaGraphBuilder(String uccFilterThreshold, String indFilterThreshold, String fdFilterThreshold) {
        this(uccFilterThreshold, indFilterThreshold, fdFilterThreshold, "NONE", "COMPLETE");
    }

    SchemaGraphBuilder(String uccFilterThreshold, String indFilterThreshold, String fdFilterThreshold, String constraintWeighting) {
        this(uccFilterThreshold, indFilterThreshold, fdFilterThreshold, constraintWeighting, "COMPLETE");
    }

    SchemaGraphBuilder(String uccFilterThreshold, String indFilterThreshold, String fdFilterThreshold, String constraintWeighting, String graphEncoding) {
        this(uccFilterThreshold, indFilterThreshold, fdFilterThreshold, constraintWeighting, graphEncoding, "", "");
    }

    SchemaGraphBuilder(String uccFilterThreshold, String indFilterThreshold, String fdFilterThreshold, String constraintWeighting, String graphEncoding, String dependencyTopK, String fdAllowListFile) {
        this.dependencyFilter = new DependencyFilter(uccFilterThreshold, indFilterThreshold, fdFilterThreshold, dependencyTopK);
        this.qualityWeightedConstraints = "QUALITY".equalsIgnoreCase(Objects.toString(constraintWeighting, "NONE"));
        this.gatedConstraints = "GATED".equalsIgnoreCase(Objects.toString(constraintWeighting, "NONE"));
        this.rankWeightedConstraints =
                "QUALITY_RANK".equalsIgnoreCase(Objects.toString(constraintWeighting, "NONE"));
        this.constantWeightedConstraints =
                "CONSTANT".equalsIgnoreCase(Objects.toString(constraintWeighting, "NONE"));
        this.constantWeight = Double.parseDouble(System.getProperty("smx.sf.constantWeight", "0.35"));
        this.topkHybridConstraints =
                "TOPK_HYBRID".equalsIgnoreCase(Objects.toString(constraintWeighting, "NONE"));
        this.topK = Integer.getInteger("smx.sf.topK", 1);
        this.topKPercent = Double.parseDouble(System.getProperty("smx.sf.topKPercent", "0"));
        this.topkHigh = Double.parseDouble(System.getProperty("smx.sf.topkHigh", "0.20"));
        this.topkLow = Double.parseDouble(System.getProperty("smx.sf.topkLow", "0.05"));
        if (this.constantWeightedConstraints
                && (this.constantWeight < 0.0 || this.constantWeight > 1.0)) {
            throw new IllegalArgumentException(
                    "smx.sf.constantWeight must lie in [0,1]; LabelEdge holds a weight to "
                            + "that range and a structural edge already weighs 1.0");
        }
        this.uccThresholdRaw = Objects.toString(uccFilterThreshold, "");
        this.indThresholdRaw = Objects.toString(indFilterThreshold, "");
        this.gateHigh = Double.parseDouble(System.getProperty("smx.sf.gate.high", "0.6"));
        this.gateLow = Double.parseDouble(System.getProperty("smx.sf.gate.low", "0.35"));
        this.gateIncrease = Double.parseDouble(System.getProperty("smx.sf.gate.inc", "1.2"));
        this.gateDecrease = Double.parseDouble(System.getProperty("smx.sf.gate.dec", "0.9"));
        if (this.gatedConstraints
                && (this.gateIncrease <= 0 || this.gateDecrease <= 0)) {
            throw new IllegalArgumentException(
                    "smx.sf.gate.inc and .dec must be positive; a non-positive band "
                            + "silently deletes every dependency edge it covers");
        }
        if (this.gatedConstraints && this.gateLow > this.gateHigh) {
            throw new IllegalArgumentException(
                    "smx.sf.gate.low (" + this.gateLow + ") must not exceed smx.sf.gate.high ("
                            + this.gateHigh + "); an inverted band silently makes every edge decrease");
        }
        this.graphEncoding = Objects.toString(graphEncoding, "COMPLETE");
        this.fdAllowListFile = Objects.toString(fdAllowListFile, "");
    }

    private boolean isQuickEncoding() {
        return "QUICK".equalsIgnoreCase(this.graphEncoding);
    }

    /**
     * Canonical signature of an FD, matching what the allow-list files contain.
     *
     * Determinant columns are sorted so that the signature does not depend on
     * the order the profiler happened to emit them in.
     */
    private static String signatureOf(FunctionalDependency fd) {
        List<String> determinant = new ArrayList<>();
        for (Column column : fd.getDeterminant()) {
            determinant.add(column.getTable().getName() + "." + column.getLabel());
        }
        Collections.sort(determinant);
        Column dependant = fd.getDependant();
        return String.join(",", determinant) + "->"
                + dependant.getTable().getName() + "." + dependant.getLabel();
    }

    /**
     * Restricts the FDs to those named in a file inside the database directory.
     *
     * Used to test whether *which* dependencies are kept matters beyond how
     * many: an externally chosen subset (for instance, the FDs that have a
     * counterpart in the other schema) can be compared against a random subset
     * of the same size. Absent or empty configuration leaves the input alone.
     */
    private Collection<FunctionalDependency> applyAllowList(Collection<FunctionalDependency> fds, Database db) {
        if (this.fdAllowListFile == null || this.fdAllowListFile.isBlank()) {
            return fds;
        }
        // The database directory holds only table files -- InputReader parses
        // everything it finds there -- so allow-lists live beside the profiler
        // output in metadata/<side>/ instead.
        Path listPath = Paths.get(db.getPath()).getParent()
                .resolve("metadata").resolve(db.getName()).resolve(this.fdAllowListFile);
        Set<String> allowed;
        try {
            allowed = new HashSet<>(Files.readAllLines(listPath));
        } catch (IOException e) {
            log.warn("No FD allow-list at {}; keeping all FDs", listPath);
            return fds;
        }
        allowed.removeIf(String::isBlank);

        List<FunctionalDependency> kept = new ArrayList<>();
        for (FunctionalDependency fd : fds) {
            if (allowed.contains(signatureOf(fd))) {
                kept.add(fd);
            }
        }
        log.info("FD allow-list {}: {} -> {}", this.fdAllowListFile, fds.size(), kept.size());
        return kept;
    }


    /**
     * TODO 40c intervention: order dependencies by structural position.
     *
     * Dependencies are read into hash sets ({@code InputReader.readFDFile} and
     * its UCC/IND siblings), and {@code Column.hashCode()} is derived from the
     * column label. A per-column recoding therefore changes the order in which
     * dependency edges enter the schema graph, even though the dependency set
     * itself is an exact image. That reorders the connectivity graph, which
     * reorders the floating-point accumulation in the flooding loop -- the
     * residual drift left over once the datatype nodes are gone (TODO 40b).
     *
     * This switch sorts them by table and column position instead, which no
     * relabelling can move. The default path is unchanged.
     */
    private static boolean canonicalDependencyOrder() {
        return Boolean.getBoolean("smx.sf.canonicalDependencyOrder");
    }

    /** Position of every column in database order; invariant under relabelling. */
    private static Map<Column, Integer> positionsOf(Database db) {
        Map<Column, Integer> positions = new HashMap<>();
        int next = 0;
        for (Table table : db.getTables()) {
            for (Column column : table.getColumns()) {
                positions.put(column, next++);
            }
        }
        return positions;
    }

    /** Sorted positions of a column set, so determinant order cannot vary either. */
    private static List<Integer> positionKey(Collection<Column> columns, Map<Column, Integer> positions) {
        List<Integer> key = new ArrayList<>();
        for (Column column : columns) {
            key.add(positions.getOrDefault(column, Integer.MAX_VALUE));
        }
        Collections.sort(key);
        return key;
    }

    private static int compareKeys(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = Integer.compare(a.get(i), b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    /** Columns of one dependency, in position order rather than hash order. */
    private static List<Column> inPositionOrder(Collection<Column> columns, Map<Column, Integer> positions) {
        List<Column> ordered = new ArrayList<>(columns);
        ordered.sort(Comparator.comparingInt(c -> positions.getOrDefault(c, Integer.MAX_VALUE)));
        return ordered;
    }


    /** Insertion position of every schema-graph node; invariant under relabelling. */
    private static Map<Node, Integer> insertionPositions(Graph<Node, LabelEdge> graph) {
        Map<Node, Integer> positions = new IdentityHashMap<>();
        int next = 0;
        for (Node node : graph.vertexSet()) {
            positions.put(node, next++);
        }
        return positions;
    }

    /** A map pair named by where its two nodes sit, not by what they are called. */
    private static String canonicalId(NodePair pair, Map<Node, Integer> pos1, Map<Node, Integer> pos2) {
        Integer a = pos1.get(pair.getFirstNode());
        Integer b = pos2.get(pair.getSecondNode());
        if (a == null) {
            a = -1 - orDefault(pos2.get(pair.getFirstNode()));
        }
        if (b == null) {
            b = -1 - orDefault(pos1.get(pair.getSecondNode()));
        }
        return a + "/" + b;
    }

    private static int orDefault(Integer value) {
        return value == null ? 0 : value;
    }

    private static int pairVertexDigest(Collection<NodePair> vertices, Map<Node, Integer> pos1, Map<Node, Integer> pos2) {
        StringBuilder out = new StringBuilder();
        for (NodePair pair : vertices) {
            out.append(canonicalId(pair, pos1, pos2)).append(',');
        }
        return out.toString().hashCode();
    }

    private static int connectivityEdgeDigest(Graph<NodePair, LabelEdge> graph, Map<Node, Integer> pos1, Map<Node, Integer> pos2) {
        StringBuilder out = new StringBuilder();
        for (LabelEdge edge : graph.edgeSet()) {
            out.append(canonicalId(graph.getEdgeSource(edge), pos1, pos2)).append('>')
                    .append(canonicalId(graph.getEdgeTarget(edge), pos1, pos2)).append(':')
                    .append(edge.getLabel()).append('@')
                    .append(Double.doubleToLongBits(edge.getWeight())).append(',');
        }
        return out.toString().hashCode();
    }

    static Graph<NodePair, LabelEdge> createConnectivityGraph(Graph<Node, LabelEdge> graph1, Graph<Node, LabelEdge> graph2) {

        Graph<NodePair, LabelEdge> connectivityGraph = EdgeCollisionAudit.newGraph(LabelEdge.class, "connectivity");

        // Only edges carrying the same label can ever form a connectivity edge,
        // so bucket by label instead of comparing every edge of graph1 against
        // every edge of graph2. This turns O(|E1|*|E2|) into the size of the
        // result, sum over labels of |E1_l| * |E2_l|, which matters because
        // dependency encodings add thousands of edges under a handful of labels.
        Map<String, List<LabelEdge>> edgesByLabel2 = new HashMap<>();
        for (LabelEdge edge2 : graph2.edgeSet()) {
            edgesByLabel2.computeIfAbsent(edge2.getLabel(), l -> new ArrayList<>()).add(edge2);
        }

        for (LabelEdge label1 : graph1.edgeSet()) {
            List<LabelEdge> candidates = edgesByLabel2.get(label1.getLabel());
            if (candidates == null) {
                continue;
            }

            Node sourceVertex1 = graph1.getEdgeSource(label1);
            Node targetVertex1 = graph1.getEdgeTarget(label1);

            for (LabelEdge label2 : candidates) {
                if (label1 == label2) {
                    continue;
                }

                Node sourceVertex2 = graph2.getEdgeSource(label2);
                Node targetVertex2 = graph2.getEdgeTarget(label2);

                NodePair connectedSourceNode = new NodePair(sourceVertex1, sourceVertex2);
                NodePair connectedTargetNode = new NodePair(targetVertex1, targetVertex2);

                connectivityGraph.addVertex(connectedSourceNode);
                connectivityGraph.addVertex(connectedTargetNode);

                double confidence = Math.min(label1.getWeight(), label2.getWeight());
                connectivityGraph.addEdge(connectedSourceNode, connectedTargetNode, new LabelEdge(label1.getLabel(), confidence));
            }
        }

        return connectivityGraph;
    }

    /** An ordered pair of map pairs: one slot in the propagation graph. */
    private record Endpoints(NodePair source, NodePair target) {
    }

    /** An ordered pair of schema nodes: one slot in a schema graph. */
    private record NodeEndpoints(Node source, Node target) {
    }

    /**
     * How a schema-stage dependency collision is resolved.
     *
     * The Quick encoding projects every dependency onto one edge per determinant
     * column and the container holds one edge per ordered node pair, so several
     * dependencies with different confidences compete for one slot. FIRST is the
     * historical behaviour: whichever the iteration order offered first wins,
     * and the order is a hash order. Under TOPK_HYBRID that blocks **74.6%** of
     * the promotions in the slots where selection has a decision to make
     * (`SF_TOPK_SLOT_OCCUPANCY.md`), because with k = 1 most of a column's
     * dependencies are not promoted and so most orderings put one of them first.
     *
     * MAX keeps the strongest confidence offered for a slot, which is the rule
     * that makes a selection arm mean what it says. It is opt-in rather than the
     * default because it changes every weighted arm already run; under
     * constraintWeighting=NONE, which is production, every confidence is 1.0 and
     * the two rules are identical.
     */
    private enum CollisionRule { FIRST, MAX }

    private static CollisionRule collisionRule() {
        return CollisionRule.valueOf(
                System.getProperty("smx.sf.collisionRule", "FIRST").toUpperCase());
    }

    /**
     * Adds a dependency edge under the chosen collision rule.
     *
     * Under MAX a weaker incumbent is removed and replaced, so the slot ends up
     * carrying the strongest confidence any dependency offered for it rather
     * than the first one the iteration happened to reach.
     */
    private static void addDependencyEdge(Graph<Node, LabelEdge> graph, Node source,
                                          Node target, String label, double confidence) {
        if (collisionRule() == CollisionRule.MAX) {
            LabelEdge held = graph.getEdge(source, target);
            if (held != null) {
                if (held.getWeight() >= confidence) {
                    return;
                }
                graph.removeEdge(held);
            }
        }
        graph.addEdge(source, target, new LabelEdge(label, confidence));
    }

    /**
     * Interned channel names, so tagging an edge allocates nothing per edge.
     *
     * A channel is one relation label in one direction. Review item 3 combines
     * per-channel operators with fixed weights, which requires the propagation
     * graph to remember which channel each coefficient came from.
     */
    private static final Map<String, String> CHANNELS = new HashMap<>();

    private static synchronized String channel(String label, String direction) {
        return CHANNELS.computeIfAbsent(label + "|" + direction, k -> k);
    }

    /**
     * How to treat two propagation contributions that want the same slot.
     *
     * A connectivity edge u --L--> v contributes a forward message u->v
     * normalised by the outgoing degrees at u, and a connectivity edge
     * v --L--> u contributes a reverse message that also lands on u->v,
     * normalised by the incoming degrees at u. Both are real messages and the
     * container holds one edge per ordered pair, so something has to give.
     *
     * DROP is the historical behaviour: whichever insertion the loops offered
     * first wins and the other is silently refused, which is decided by loop
     * order rather than by anything principled (SF_PARALLEL_EDGE_DEFECT.md).
     * It is the default, and it keeps the original code path rather than going
     * through the accumulator, so a run that sets nothing is byte-identical to
     * before this existed -- materialising from a map would also change the
     * order of edgeSet(), and with it the summation order in the fixpoint.
     *
     * The others combine instead of discarding. None of them is "the" correct
     * rule; they are different operators, which is the whole reason this is a
     * parameter and not a repair. Note in particular that SUM can exceed 1.0:
     * the coefficients are inverse degree products, i.e. normalisers, and the
     * sum of two normalisers is not one. CoefficientEdge forbids values outside
     * [0,1], so SUM clamps, and the clamp count is reported -- a clamp is the
     * operator telling you the rule does not fit the data structure.
     */
    private enum ParallelEdgeRule {
        DROP, MAX, AVG, SUM;

        static ParallelEdgeRule current() {
            return valueOf(System.getProperty("smx.sf.parallelEdgeRule", "DROP").toUpperCase());
        }
    }

    /**
     * Whether the propagation graph may hold more than one edge per ordered
     * vertex pair.
     *
     * COLLAPSE is the historical container, {@link DefaultDirectedWeightedGraph},
     * which permits at most one edge between an ordered pair. A connectivity
     * edge is projected twice -- once as a forward message normalised by
     * out-degree, once as the reverse message of the opposite edge normalised by
     * in-degree -- so wherever the connectivity graph holds both u--L-->v and
     * v--L-->u, the two land on one slot and the second insertion is refused.
     * On this corpus that discards 20.9% of {@code determines} insertions, 98.4%
     * of which carry a *different* coefficient (`SF_PARALLEL_EDGE_DEFECT.md`).
     * {@link ParallelEdgeRule} combines the competitors into one value, which
     * keeps the container but destroys which channel each contribution came
     * from.
     *
     * SPLIT is the repair: {@link DirectedWeightedPseudograph} holds one edge per
     * (source, target, channel), so the forward and reverse messages coexist and
     * nothing is refused. This is ranked change 3 of
     * `SF_INTERNAL_REVIEW_20260917.md` in its provenance-preserving form -- the
     * collision stops existing by construction rather than being arbitrated.
     *
     * The consumer needs no change: {@code SimilarityFlooding} builds a CSR over
     * incoming edges and sums {@code coeff[k] * sigma[src[k]]}, so two edges with
     * the same source are two terms. It also records {@code channelOf[k]}, which
     * is what lets {@code smx.sf.channelBudget} weight the channels that this
     * container now keeps apart.
     *
     * **What SPLIT does and does not change about the row bound.** The review
     * that proposed this repair assumed each P_l is row-substochastic, so that
     * keeping both messages would need a budget to restore a bound. Measured,
     * that premise is false of the current operator: under COLLAPSE the
     * propagation graph already has rows summing well above one -- 256 of 1788
     * on a ChEMBL probe, maximum row sum 146.25 -- because a coefficient is a
     * product of inverse label counts across two graphs and a node accumulates
     * many labelled neighbours. SPLIT therefore does not break a bound that
     * held; it adds mass to an operator that was never substochastic, and what
     * keeps the iteration bounded is the per-iteration division by the maximum
     * ({@code smx.sf.normalise}), not any row property.
     *
     * So SPLIT is the container repair and nothing more. A channel budget is
     * still worth pairing with it -- it is what makes the combination explicit
     * rather than incidental -- but not for the reason the review gave.
     *
     * Requires {@code smx.sf.consistentEdgeIdentity} (default since 2026-09-17):
     * with content equality on {@link CoefficientEdge}, a multigraph would refuse
     * a second edge carrying an equal coefficient and the repair would be
     * partial.
     */
    private enum ParallelEdges {
        COLLAPSE, SPLIT;

        static ParallelEdges current() {
            return valueOf(System.getProperty("smx.sf.parallelEdges", "COLLAPSE").toUpperCase());
        }
    }

    static Graph<NodePair, CoefficientEdge> inducePropagationGraph
            (Graph<NodePair, LabelEdge> connectivityGraph, Graph<Node, LabelEdge> graph1, Graph<Node, LabelEdge> graph2, PropagationCoefficientPolicy policy) {

        ParallelEdges container = ParallelEdges.current();
        Graph<NodePair, CoefficientEdge> propagationGraph =
                container == ParallelEdges.SPLIT
                        ? new DirectedWeightedPseudograph<>(CoefficientEdge.class)
                        : new DefaultDirectedWeightedGraph<>(CoefficientEdge.class);

        // Non-DROP rules collect contributions per slot and materialise once at
        // the end; DROP keeps the historical write-straight-through path.
        // Under SPLIT there is nothing to arbitrate -- the contributions no
        // longer share a slot -- so the accumulator is bypassed and an explicit
        // rule alongside SPLIT is a configuration error rather than a silent
        // precedence.
        ParallelEdgeRule rule = ParallelEdgeRule.current();
        if (container == ParallelEdges.SPLIT && rule != ParallelEdgeRule.DROP) {
            throw new IllegalArgumentException(
                    "smx.sf.parallelEdges=SPLIT keeps the colliding messages apart, so "
                            + "smx.sf.parallelEdgeRule=" + rule + " has nothing to combine. "
                            + "Set one or the other.");
        }
        Map<Endpoints, double[]> pending =
                rule == ParallelEdgeRule.DROP ? null : new LinkedHashMap<>();

        for (NodePair nodePair : connectivityGraph.vertexSet()) {
            propagationGraph.addVertex(nodePair);
        }

        Map<Node, Integer> pos1 = null, pos2 = null;
        if (Boolean.getBoolean("smx.sf.debugGraph")) {
            pos1 = insertionPositions(graph1);
            pos2 = insertionPositions(graph2);
            System.out.println("DEBUGSTAGE connectivity"
                    + " vertices=" + connectivityGraph.vertexSet().size()
                    + " edges=" + connectivityGraph.edgeSet().size()
                    + " vertexSeq=" + pairVertexDigest(connectivityGraph.vertexSet(), pos1, pos2)
                    + " edgeSeq=" + connectivityEdgeDigest(connectivityGraph, pos1, pos2));
        }

        // A node of graph1 occurs in as many map pairs as graph2 has nodes, and
        // the policy recounts its incident labels every time. Tally each node
        // once and let the policy merge the cached counts instead.
        Map<Node, Map<String, Double>> inLabels1 = new HashMap<>();
        Map<Node, Map<String, Double>> outLabels1 = new HashMap<>();
        Map<Node, Map<String, Double>> inLabels2 = new HashMap<>();
        Map<Node, Map<String, Double>> outLabels2 = new HashMap<>();

        for (NodePair nodePair : connectivityGraph.vertexSet()) {

            Node nodeGraph1 = nodePair.getFirstNode();
            Node nodeGraph2 = nodePair.getSecondNode();

            List<Map<String, Double>> propagationCoefficients = new ArrayList<>();

            try {
                propagationCoefficients = policy.combine(
                        inLabels1.computeIfAbsent(nodeGraph1, n -> PropagationCoefficientPolicy.countLabelsOf(graph1, n, true)),
                        outLabels1.computeIfAbsent(nodeGraph1, n -> PropagationCoefficientPolicy.countLabelsOf(graph1, n, false)),
                        inLabels2.computeIfAbsent(nodeGraph2, n -> PropagationCoefficientPolicy.countLabelsOf(graph2, n, true)),
                        outLabels2.computeIfAbsent(nodeGraph2, n -> PropagationCoefficientPolicy.countLabelsOf(graph2, n, false)));
            } catch (Exception e) {
                log.info("Not a valid propagation coefficient calculation policy");
            }

            Map<String, Double> countInLabelsTotal = propagationCoefficients.get(0);
            Map<String, Double> countOutLabelsTotal = propagationCoefficients.get(1);

            for (LabelEdge edge : connectivityGraph.incomingEdgesOf(nodePair)) {
                NodePair sourceNodePair = connectivityGraph.getEdgeSource(edge);
                NodePair targetNodePair = connectivityGraph.getEdgeTarget(edge);
                double coefficient = countInLabelsTotal.get(edge.getLabel()) * edge.getWeight();
                if (pending != null) {
                    collect(pending, targetNodePair, sourceNodePair, coefficient);
                    continue;
                }
                boolean inserted = propagationGraph.addEdge(targetNodePair, sourceNodePair,
                        new CoefficientEdge(coefficient, channel(edge.getLabel(), "rev")));
                if (EdgeCollisionAudit.ENABLED) {
                    CoefficientEdge held = propagationGraph.getEdge(targetNodePair, sourceNodePair);
                    EdgeCollisionAudit.propagationEdge("reverse", edge.getLabel(),
                            targetNodePair, sourceNodePair, inserted, held != null,
                            held == null ? Double.NaN : held.getCoefficient(), coefficient);
                }
            }

            for (LabelEdge edge : connectivityGraph.outgoingEdgesOf(nodePair)) {
                NodePair sourceNodePair = connectivityGraph.getEdgeSource(edge);
                NodePair targetNodePair = connectivityGraph.getEdgeTarget(edge);
                double coefficient = countOutLabelsTotal.get(edge.getLabel()) * edge.getWeight();
                if (pending != null) {
                    collect(pending, sourceNodePair, targetNodePair, coefficient);
                    continue;
                }
                boolean inserted = propagationGraph.addEdge(sourceNodePair, targetNodePair,
                        new CoefficientEdge(coefficient, channel(edge.getLabel(), "fwd")));
                if (EdgeCollisionAudit.ENABLED) {
                    CoefficientEdge held = propagationGraph.getEdge(sourceNodePair, targetNodePair);
                    EdgeCollisionAudit.propagationEdge("forward", edge.getLabel(),
                            sourceNodePair, targetNodePair, inserted, held != null,
                            held == null ? Double.NaN : held.getCoefficient(), coefficient);
                }
            }
        }

        if (pos1 != null) {
            StringBuilder edges = new StringBuilder();
            for (CoefficientEdge edge : propagationGraph.edgeSet()) {
                edges.append(canonicalId(propagationGraph.getEdgeSource(edge), pos1, pos2))
                        .append('>')
                        .append(canonicalId(propagationGraph.getEdgeTarget(edge), pos1, pos2))
                        .append(':')
                        .append(Double.doubleToLongBits(edge.getCoefficient()))
                        .append(',');
            }
            System.out.println("DEBUGSTAGE propagation"
                    + " vertices=" + propagationGraph.vertexSet().size()
                    + " edges=" + propagationGraph.edgeSet().size()
                    + " vertexSeq=" + pairVertexDigest(propagationGraph.vertexSet(), pos1, pos2)
                    + " edgeSeq=" + edges.toString().hashCode());
        }

        if (pending != null) {
            int collided = 0, clamped = 0;
            for (Map.Entry<Endpoints, double[]> entry : pending.entrySet()) {
                double[] slot = entry.getValue();
                double value = switch (rule) {
                    case MAX -> slot[1];
                    case AVG -> slot[0] / slot[2];
                    case SUM -> slot[0];
                    case DROP -> slot[3];
                };
                if (slot[2] > 1) {
                    collided++;
                }
                if (value > 1.0) {
                    value = 1.0;
                    clamped++;
                }
                propagationGraph.addEdge(entry.getKey().source(), entry.getKey().target(),
                        new CoefficientEdge(value));
            }
            if (Boolean.getBoolean("smx.sf.printGraphSizes")) {
                System.out.println("PARALLELRULE rule=" + rule + " slots=" + pending.size()
                        + " contested=" + collided + " clamped=" + clamped);
            }
        }

        // Both schema graphs and the connectivity graph are finished by the time
        // the propagation graph is, so this is where a whole construction's
        // refused insertions can be reported in one place.
        EdgeCollisionAudit.report();

        // Sizes of the graphs that were actually built, for callers that need to
        // compare two configurations without instrumenting the construction.
        //
        // EdgeCollisionAudit cannot serve that purpose. Its AuditedGraph and its
        // endpoint map allocate objects while the graphs are being built, which
        // shifts the identity-hash sequence -- and identity hashes are exactly
        // what decides which insertions JGraphT refuses through containsEdge.
        // Enabling the audit therefore changes which edges are lost, so its
        // per-scenario counts describe the audited run rather than the ordinary
        // one. This prints after construction and allocates nothing during it,
        // so the run it measures is the run that would have happened anyway.
        if (Boolean.getBoolean("smx.sf.printGraphSizes")) {
            // Total propagation mass: the sum of every coefficient the operator
            // will multiply a neighbour value by. The parallel-edge rules differ
            // only in contested slots, and every contested slot carries the
            // `determines` label, so the difference between two rules' totals is
            // exactly the dependency-channel mass one restores over the other.
            double propagationMass = 0.0;
            for (CoefficientEdge edge : propagationGraph.edgeSet()) {
                propagationMass += edge.getCoefficient();
            }

            // Whether the container actually kept the colliding messages apart,
            // and what that costs in row mass. Each coefficient is an inverse
            // degree, so a row carrying both the forward and the reverse message
            // of the same relation can exceed 1 -- which is the bound a channel
            // budget has to restore. Reported rather than assumed.
            Map<Endpoints, Integer> perSlot = new HashMap<>();
            for (CoefficientEdge edge : propagationGraph.edgeSet()) {
                perSlot.merge(new Endpoints(propagationGraph.getEdgeSource(edge),
                        propagationGraph.getEdgeTarget(edge)), 1, Integer::sum);
            }
            int parallelSlots = 0, parallelEdges = 0;
            for (int n : perSlot.values()) {
                if (n > 1) {
                    parallelSlots++;
                    parallelEdges += n;
                }
            }
            double maxRow = 0.0;
            int rowsOverOne = 0;
            for (NodePair v : propagationGraph.vertexSet()) {
                double rowSum = 0.0;
                for (CoefficientEdge edge : propagationGraph.incomingEdgesOf(v)) {
                    rowSum += edge.getCoefficient();
                }
                if (rowSum > 1.0 + 1e-9) {
                    rowsOverOne++;
                }
                maxRow = Math.max(maxRow, rowSum);
            }
            System.out.println("PARALLELSPLIT container=" + container
                    + " slots=" + perSlot.size()
                    + " parallel_slots=" + parallelSlots
                    + " edges_on_parallel_slots=" + parallelEdges
                    + " rows_over_one=" + rowsOverOne
                    + " max_row_sum=" + maxRow);
            System.out.println("GRAPHSIZE propagation_mass=" + propagationMass
                    + " connectivity_vertices=" + connectivityGraph.vertexSet().size()
                    + " connectivity_edges=" + connectivityGraph.edgeSet().size()
                    + " propagation_vertices=" + propagationGraph.vertexSet().size()
                    + " propagation_edges=" + propagationGraph.edgeSet().size()
                    + " schema_vertices=" + (graph1.vertexSet().size() + graph2.vertexSet().size())
                    + " schema_edges=" + (graph1.edgeSet().size() + graph2.edgeSet().size()));
        }

        return propagationGraph;
    }

    /**
     * Layer 3b (session C): extra column-to-column edges computed outside Schematch.
     *
     * Empty, the default, adds nothing. Otherwise, for the schema at <scenario>/<side>, the file
     * <extraEdgesDir>/<dataset>/<scenario>/<side>.tsv is read if it exists: one edge per line,
     * "label TAB table TAB fromColumn TAB toColumn TAB weight", between the two columns' ID nodes of that
     * table, labelled with the given label. The label is its own propagation channel (coefficients are
     * counted per label); the weight multiplies the coefficients like a dependency confidence. This lets
     * an information family enter the graph as edges between the columns of one table (e.g. family 6,
     * distribution similarity) without a Java change per family. The schema graph keeps one edge per
     * ordered node pair, so an extra edge between two columns that already have an edge (a projected
     * FD, say) is not added; such collisions are counted in the log line.
     *
     * Class nodes (families 7 and 13): a target "@class:<value>" links the column to a class node shared by
     * every column of this schema with the same value under the same label, built exactly like a datatype
     * node: column ID -(label)-> class ID node -(name)-> literal <value>, and class ID node -(type)-> one
     * type node "<label>Class". Literal seeds therefore compare class names across schemas as they compare
     * datatype names. Node.equals compares values only when a node has no table, so a class literal equal to
     * any other node of the graph would merge with it; that is refused with an exception.
     */
    private static final String EXTRA_EDGES_DIR = System.getProperty("smx.sf.extraEdgesDir", "");

    private void extraEdges(Database db, Graph<Node, LabelEdge> graphRepresentation) {
        Path schema = Paths.get(db.getPath());
        Path scenario = schema.getParent();
        Path file = Paths.get(EXTRA_EDGES_DIR, scenario.getParent().getFileName().toString(),
                scenario.getFileName().toString(), schema.getFileName().toString() + ".tsv");
        if (!Files.isRegularFile(file)) {
            log.info("Extra edges: none for {}", file);
            return;
        }
        Map<String, Map<String, Column>> columns = new HashMap<>();
        for (Table table : db.getTables()) {
            Map<String, Column> byLabel = new HashMap<>();
            for (Column column : table.getColumns()) {
                byLabel.put(column.getLabel(), column);
            }
            columns.put(table.getName(), byLabel);
        }
        int added = 0, collided = 0;
        Map<String, Node> classIds = new HashMap<>();
        Map<String, Node> typeNodes = new HashMap<>();
        Set<Node> before = new HashSet<>(graphRepresentation.vertexSet());
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            if (f.length != 5) {
                throw new IllegalArgumentException("extra edges: expected 5 tab-separated fields in " + file + ": " + line);
            }
            Map<String, Column> byLabel = columns.get(f[1]);
            boolean toClass = f[3].startsWith("@class:");
            if (byLabel == null || !byLabel.containsKey(f[2]) || (!toClass && !byLabel.containsKey(f[3]))) {
                throw new IllegalArgumentException("extra edges: unknown table or column in " + file + ": " + line);
            }
            Node from = idNodeOf(byLabel.get(f[2]), graphRepresentation);
            Node to;
            if (toClass) {
                String value = f[3].substring("@class:".length());
                String key = f[0] + "	" + value;
                to = classIds.get(key);
                if (to == null) {
                    Node literal = new Node(value, NodeType.COLUMN_TYPE, null, false, null, null, null);
                    Node type = typeNodes.computeIfAbsent(f[0], l -> new Node(l + "Class", NodeType.COLUMN_TYPE, null, false, null, null, null));
                    for (Node n : List.of(literal, type)) {
                        if (before.contains(n)) {
                            throw new IllegalArgumentException("extra edges: class node '" + n.getValue()
                                    + "' equals an existing node of the schema graph (" + file + ")");
                        }
                    }
                    if (classIds.values().stream().anyMatch(c -> c.getNameNode().equals(literal))) {
                        throw new IllegalArgumentException("extra edges: class literal '" + value
                                + "' used under two labels (" + file + ")");
                    }
                    to = new Node("ClassID" + (classIds.size() + 1), NodeType.COLUMN_TYPE, null, true, literal, null, null);
                    graphRepresentation.addVertex(literal);
                    graphRepresentation.addVertex(type);
                    graphRepresentation.addVertex(to);
                    graphRepresentation.addEdge(to, literal, new LabelEdge("name"));
                    graphRepresentation.addEdge(to, type, new LabelEdge("type"));
                    classIds.put(key, to);
                }
            } else {
                to = idNodeOf(byLabel.get(f[3]), graphRepresentation);
            }
            if (graphRepresentation.addEdge(from, to, new LabelEdge(f[0], Double.parseDouble(f[4])))) {
                added++;
            } else {
                collided++;
            }
        }
        log.info("Extra edges from {}: {} added, {} not added (pair already has an edge); {} class nodes",
                file, added, collided, classIds.size());
    }

    Graph<Node, LabelEdge> transformIntoGraphRepresentationSchema(Database db) {

        Graph<Node, LabelEdge> graphRepresentation = EdgeCollisionAudit.newGraph(LabelEdge.class, "schema");

        // QUALITY_RANK needs the per-type score distribution of this schema
        // before any edge is weighted. Done once here rather than at each of the
        // seven per-type loops, and over the same filtered collections those
        // loops iterate, so the ranks match the edges actually built.
        prepareRankDistributions(db);

        buildGraphRepresentation(db, graphRepresentation);

        //Extending the schema-graph with dependency information:

        boolean quick = isQuickEncoding();

        // The Complete encoding gives every dependency its own ID node and links
        // all of them to one shared Constraint node. Both are costly: the ID
        // nodes make the pairwise connectivity graph quadratic in the number of
        // dependencies, and the shared Constraint node turns that quadratic set
        // into edges converging on a single map pair. The Quick encoding adds
        // no dependency nodes at all, so it is omitted there.
        Node constraintNode = null;
        if (!quick) {
            constraintNode = new Node("Constraint", NodeType.CONSTRAINT, null, false, null, null, null);
            graphRepresentation.addVertex(constraintNode);
        }

        if(!this.dependencyFilter.getFdFilterThreshold().isEmpty()) {
            if (quick) {
                functionalDependenciesQuick(db, graphRepresentation);
            } else {
                functionalDependencies(db, graphRepresentation, constraintNode);
            }
        }

        if(!this.dependencyFilter.getUccFilterThreshold().isEmpty()) {
            if (quick) {
                uniqueColumnCombinationsQuick(db, graphRepresentation);
            } else {
                uniqueColumnCombinations(db, graphRepresentation, constraintNode);
            }
        }

        if(!this.dependencyFilter.getIndFilterThreshold().isEmpty()) {
            if (quick) {
                inclusionDependenciesQuick(db, graphRepresentation);
            } else {
                inclusionDependencies(db, graphRepresentation, constraintNode);
            }
        }

        if (!EXTRA_EDGES_DIR.isEmpty()) {
            extraEdges(db, graphRepresentation);
        }

        if (Boolean.getBoolean("smx.sf.debugGraph")) {
            Map<String, Integer> perLabel = new TreeMap<>();
            for (LabelEdge edge : graphRepresentation.edgeSet()) {
                perLabel.merge(edge.getLabel(), 1, Integer::sum);
            }
            Map<String, Integer> perType = new TreeMap<>();
            for (Node node : graphRepresentation.vertexSet()) {
                perType.merge(String.valueOf(node.getNodeType()), 1, Integer::sum);
            }
            // Deliberately no dependency-filter call here: it memoises gpdep
            // scores, so a diagnostic that made one would not be read-only.
            int fdCount = -1;
            Map<Node, Integer> position = new IdentityHashMap<>();
            List<Node> ordered = new ArrayList<>(graphRepresentation.vertexSet());
            for (int i = 0; i < ordered.size(); i++) {
                position.put(ordered.get(i), i);
            }
            StringBuilder edgeSequence = new StringBuilder();
            StringBuilder edgeWeights = new StringBuilder();
            for (LabelEdge edge : graphRepresentation.edgeSet()) {
                edgeSequence.append(position.get(graphRepresentation.getEdgeSource(edge)))
                        .append('>')
                        .append(position.get(graphRepresentation.getEdgeTarget(edge)))
                        .append(':').append(edge.getLabel()).append(',');
                edgeWeights.append(Double.doubleToLongBits(edge.getWeight())).append(',');
            }
            StringBuilder vertexSequence = new StringBuilder();
            for (Node node : graphRepresentation.vertexSet()) {
                vertexSequence.append(node.getNodeType()).append(',');
            }
            System.out.println("DEBUGSCHEMA db=" + db.getName()
                    + " vertices=" + graphRepresentation.vertexSet().size()
                    + " edges=" + graphRepresentation.edgeSet().size()
                    + " fdsAfterFilter=" + fdCount
                    + " edgeTopology=" + edgeSequence.toString().hashCode()
                    + " edgeWeights=" + edgeWeights.toString().hashCode()
                    + " vertexSeq=" + vertexSequence.toString().hashCode()
                    + " byLabel=" + perLabel + " byType=" + perType);
        }

        return graphRepresentation;
    }

    /**
     * Records one contribution to a propagation slot.
     *
     * The slot carries [sum, max, count, first] so every rule can be answered
     * from one pass, including DROP, which is kept here for completeness even
     * though the production path never reaches it.
     */
    private static void collect(Map<Endpoints, double[]> pending, NodePair source,
                                NodePair target, double coefficient) {
        pending.compute(new Endpoints(source, target), (key, slot) -> {
            if (slot == null) {
                return new double[]{coefficient, coefficient, 1, coefficient};
            }
            slot[0] += coefficient;
            slot[1] = Math.max(slot[1], coefficient);
            slot[2] += 1;
            return slot;
        });
    }

    /**
     * Resolves the ID node that represents a column in the schema graph.
     *
     * Columns are reached through the incoming edge of their name node, which
     * is how the Complete builders already locate them; sharing one helper
     * keeps the Quick builders consistent with them.
     */
    private static Node idNodeOf(Column column, Graph<Node, LabelEdge> graphRepresentation) {
        Node nameNode = new Node(column.getLabel(), NodeType.COLUMN, column.getDatatype(), false, null, column.getTable(), null);
        LabelEdge edgeFromId = graphRepresentation.incomingEdgesOf(nameNode).stream().findFirst().orElseThrow();
        return graphRepresentation.getEdgeSource(edgeFromId);
    }

    /**
     * FD-Quick: a determinant column points directly at the dependent column.
     *
     * No FD node is created, so two schemas with n and m dependencies no longer
     * contribute n*m map pairs to the connectivity graph.
     */
    private void functionalDependenciesQuick(Database db, Graph<Node, LabelEdge> graphRepresentation) {
        Collection<FunctionalDependency> functionalDependencies =
                db.getMetadata().getMeaningfulFunctionalDependencies();

        Collection<FunctionalDependency> selected =
                dependencyFilter.filterFunctionalDependencies(applyAllowList(functionalDependencies, db));
        Map<Column, Integer> positions = positionsOf(db);
        if (canonicalDependencyOrder()) {
            List<FunctionalDependency> ordered = new ArrayList<>(selected);
            ordered.sort(Comparator
                    .comparingInt((FunctionalDependency fd) -> positions.getOrDefault(fd.getDependant(), Integer.MAX_VALUE))
                    .thenComparing(fd -> positionKey(fd.getDeterminant(), positions), SchemaGraphBuilder::compareKeys));
            selected = ordered;
        }

        // Every confidence that was offered for a slot, when the weight profile
        // is being measured. Several functional dependencies can project onto
        // the same column pair, and the container keeps only the first, so what
        // the graph ends up propagating is a *sample* of what was offered.
        boolean profile = Boolean.getBoolean("smx.sf.printWeightProfile");

        for (FunctionalDependency fd : selected) {
            double confidence = dependencyConfidence(fd);
            Node dependantIDNode = idNodeOf(fd.getDependant(), graphRepresentation);

            Collection<Column> determinants = canonicalDependencyOrder()
                    ? inPositionOrder(fd.getDeterminant(), positions) : fd.getDeterminant();
            for (Column determinant : determinants) {
                Node determinantIDNode = idNodeOf(determinant, graphRepresentation);
                addDependencyEdge(graphRepresentation, determinantIDNode, dependantIDNode,
                        "determines", confidence);
            }
        }
        if (profile) {
            reportWeightProfile(db, graphRepresentation, selected, positions);
        }
    }

    /**
     * Compares the dependency confidences that were *offered* with the ones the
     * graph actually propagates.
     *
     * The Quick encoding projects each functional dependency onto one edge per
     * determinant column, so several dependencies with different confidences can
     * land on the same column pair. The container keeps the first and refuses
     * the rest, which means the weights SF propagates are a sample of the
     * weights the weighting scheme computed -- picked by iteration order rather
     * than by anything meaningful. This measures how much that matters: how far
     * apart the competing confidences are, where in that spread the retained one
     * sits, and whether the weight the graph carries differs from the mean of
     * the weights offered for that same slot. The comparison is per slot: a slot
     * offered four confidences and keeping one must be compared against the mean
     * of its own four, not against the mean over every offering in the schema.
     *
     * Under constraintWeighting=NONE every confidence is 1.0 and every number
     * below is degenerate, which is itself the answer for the production path.
     * It is only meaningful under QUALITY, QUALITY_RANK, GATED and CONSTANT.
     *
     * Read-only and printed after the edges exist; it recomputes confidences
     * rather than capturing them during construction, so it allocates nothing
     * while the graph is being built.
     */
    private void reportWeightProfile(Database db, Graph<Node, LabelEdge> graphRepresentation,
                                     Collection<FunctionalDependency> selected,
                                     Map<Column, Integer> positions) {
        Map<NodeEndpoints, List<Double>> offered = new LinkedHashMap<>();
        for (FunctionalDependency fd : selected) {
            double confidence = dependencyConfidence(fd);
            Node dependantIDNode = idNodeOf(fd.getDependant(), graphRepresentation);
            Collection<Column> determinants = canonicalDependencyOrder()
                    ? inPositionOrder(fd.getDeterminant(), positions) : fd.getDeterminant();
            for (Column determinant : determinants) {
                Node determinantIDNode = idNodeOf(determinant, graphRepresentation);
                offered.computeIfAbsent(new NodeEndpoints(determinantIDNode, dependantIDNode),
                        k -> new ArrayList<>()).add(confidence);
            }
        }

        // Compared per slot, not per offering. A slot that several dependencies
        // project onto contributes one retained value and many offered ones, so
        // averaging the two populations directly would compare a mean over 405
        // numbers with a mean over 1,658 and call the difference a bias.
        int offeredCount = 0, contested = 0, distinct = 0;
        int retainedMax = 0, retainedMin = 0, retainedMid = 0;
        double offeredSlotMeanSum = 0.0, retainedSum = 0.0, deltaSum = 0.0;
        double spreadSum = 0.0, spreadMax = 0.0;
        int slotsWithEdge = 0;
        for (Map.Entry<NodeEndpoints, List<Double>> entry : offered.entrySet()) {
            List<Double> values = entry.getValue();
            offeredCount += values.size();
            LabelEdge held = graphRepresentation.getEdge(entry.getKey().source(),
                    entry.getKey().target());
            if (held == null) {
                continue;
            }
            slotsWithEdge++;
            double slotMean = 0.0;
            for (double v : values) {
                slotMean += v;
            }
            slotMean /= values.size();
            offeredSlotMeanSum += slotMean;
            retainedSum += held.getWeight();
            deltaSum += held.getWeight() - slotMean;
            if (values.size() > 1) {
                contested++;
                double lo = Collections.min(values), hi = Collections.max(values);
                if (hi > lo) {
                    distinct++;
                    spreadSum += hi - lo;
                    spreadMax = Math.max(spreadMax, hi - lo);
                    if (held.getWeight() >= hi) {
                        retainedMax++;
                    } else if (held.getWeight() <= lo) {
                        retainedMin++;
                    } else {
                        retainedMid++;
                    }
                }
            }
        }
        System.out.printf("WEIGHTPROFILE db=%s offered=%d slots=%d contested=%d distinct=%d "
                        + "mean_offered=%.6f mean_retained=%.6f delta_mean=%+.6f "
                        + "spread_mean=%.6f spread_max=%.6f max=%d mid=%d min=%d%n",
                db.getName(), offeredCount, slotsWithEdge, contested, distinct,
                slotsWithEdge == 0 ? 0.0 : offeredSlotMeanSum / slotsWithEdge,
                slotsWithEdge == 0 ? 0.0 : retainedSum / slotsWithEdge,
                slotsWithEdge == 0 ? 0.0 : deltaSum / slotsWithEdge,
                distinct == 0 ? 0.0 : spreadSum / distinct,
                spreadMax, retainedMax, retainedMid, retainedMin);
    }

    /**
     * UCC-Quick: columns of a UCC point at a node standing for its arity.
     *
     * Size nodes are shared by all UCCs of the same arity, so the number of
     * added nodes is bounded by the number of distinct sizes rather than by
     * the number of unique column combinations.
     */
    private void uniqueColumnCombinationsQuick(Database db, Graph<Node, LabelEdge> graphRepresentation) {
        Collection<UniqueColumnCombination> uccs = db.getMetadata().getUccs().stream().toList();

        Collection<UniqueColumnCombination> selected = dependencyFilter.filterUniqueColumnCombinations(uccs);
        Map<Column, Integer> positions = positionsOf(db);
        if (canonicalDependencyOrder()) {
            List<UniqueColumnCombination> ordered = new ArrayList<>(selected);
            ordered.sort((a, b) -> compareKeys(positionKey(a.getColumnCombination(), positions),
                                               positionKey(b.getColumnCombination(), positions)));
            selected = ordered;
        }

        for (UniqueColumnCombination ucc : selected) {
            double confidence = dependencyConfidence(ucc);
            int uccSize = ucc.getColumnCombination().size();

            Node uccSizeNode = new Node("UCC#" + uccSize, NodeType.CONSTRAINT, null, false, null, null, null);
            graphRepresentation.addVertex(uccSizeNode);

            Collection<Column> uccColumns = canonicalDependencyOrder()
                    ? inPositionOrder(ucc.getColumnCombination(), positions) : ucc.getColumnCombination();
            for (Column column : uccColumns) {
                addDependencyEdge(graphRepresentation, idNodeOf(column, graphRepresentation),
                        uccSizeNode, "ucc", confidence);
            }
        }
    }

    /**
     * IND-Quick: a referenced column points directly at the dependent column.
     */
    private void inclusionDependenciesQuick(Database db, Graph<Node, LabelEdge> graphRepresentation) {
        List<InclusionDependency> inds = db.getMetadata().getInds().stream().toList();

        Collection<InclusionDependency> selected = dependencyFilter.filterInclusionDependencies(inds);
        Map<Column, Integer> positions = positionsOf(db);
        if (canonicalDependencyOrder()) {
            List<InclusionDependency> ordered = new ArrayList<>(selected);
            ordered.sort(Comparator
                    .comparing((InclusionDependency ind) -> positionKey(ind.getReferenced(), positions), SchemaGraphBuilder::compareKeys)
                    .thenComparing(ind -> positionKey(ind.getDependant(), positions), SchemaGraphBuilder::compareKeys));
            selected = ordered;
        }

        for (InclusionDependency ind : selected) {
            double confidence = dependencyConfidence(ind);

            Collection<Column> referencedColumns = canonicalDependencyOrder()
                    ? inPositionOrder(ind.getReferenced(), positions) : ind.getReferenced();
            for (Column referenced : referencedColumns) {
                Node referencedIDNode = idNodeOf(referenced, graphRepresentation);
                Collection<Column> dependantColumns = canonicalDependencyOrder()
                        ? inPositionOrder(ind.getDependant(), positions) : ind.getDependant();
                for (Column dependant : dependantColumns) {
                    addDependencyEdge(graphRepresentation, referencedIDNode,
                            idNodeOf(dependant, graphRepresentation), "contains", confidence);
                }
            }
        }
    }

    /**
     * Root the graph at the table rather than at the schema.
     *
     * Melnik et al.'s worked example is table-rooted; this builder is
     * schema-rooted, which the thesis records as a deliberate choice
     * ("Alternatively, the graphs can be rooted at the table node. However,
     * this setup restricts matching to a single source and target table").
     * The audited bridge already requires exactly one table per side, so the
     * restriction does not bind here.
     *
     * The distinction matters for SF_CANONICAL_CHECK.md: schema-rooting adds the
     * DATABASE-level nodes that carry part of the unnormalised Perron vector.
     * Default is unchanged (schema-rooted).
     */
    /**
     * T1 (layer 3b, session C; the fifth external review): table-membership ablation.
     *
     * ORIGINAL, the default, builds the schema graph as before. The other modes change only which table node sends
     * the `column` edge to each column; every column keeps its own name literal, datatype node, class nodes and
     * dependency edges, and its nodes keep their table (the seed lookup goes through the name nodes):
     *   COLLAPSED  one table node per schema (the first table's), and every column hangs under it;
     *   PERMUTED   the original table nodes, with the columns reassigned to them at random so that every table keeps
     *              its number of columns; seeded by "<dataset>/<scenario>/<side>" from the schema's path, so the same
     *              permutation on every host.
     * By Proposition 1 the table pair is the only structural channel of the dependency-free schema graph:
     * COLLAPSED makes it constant (the single-table regime), PERMUTED keeps its shape and breaks its meaning.
     * One TABLEMEMBERSHIP line per schema reports the mode and the assignment.
     */
    private static final String TABLE_MEMBERSHIP =
            System.getProperty("smx.sf.tableMembership", "ORIGINAL").toUpperCase();
    /** PERMUTED draw: 0 (default) uses the key itself, k > 0 the key + "#k", so draw 0 is the T1 screen's permutation. */
    private static final int TABLE_MEMBERSHIP_DRAW =
            Integer.parseInt(System.getProperty("smx.sf.tableMembershipDraw", "0"));

    static {
        if (!List.of("ORIGINAL", "COLLAPSED", "PERMUTED").contains(TABLE_MEMBERSHIP)) {
            throw new IllegalArgumentException("smx.sf.tableMembership must be ORIGINAL, COLLAPSED or PERMUTED");
        }
    }

    private static final boolean TABLE_ROOTED =
            Boolean.getBoolean("smx.sf.tableRooted");

    private void buildGraphRepresentation(Database db, Graph<Node, LabelEdge> graphRepresentation) {
        Node schemaNode = new Node("Schema", NodeType.DATABASE, null, false, null, null, null);
        Node tableNode = new Node("Table", NodeType.TABLE, null, false, null, null, null);
        Node columnNode = new Node("Column", NodeType.COLUMN, null, false, null, null, null);
        Node columnTypeNode = new Node("ColumnType", NodeType.COLUMN_TYPE, null, false, null, null, null);

        if (!TABLE_ROOTED) {
            graphRepresentation.addVertex(schemaNode);
        }
        graphRepresentation.addVertex(tableNode);
        graphRepresentation.addVertex(columnNode);
        graphRepresentation.addVertex(columnTypeNode);

        int uniqueID = 1;

        Node currentDatabaseNode = null;
        if (!TABLE_ROOTED) {
            Node databaseName = new Node(db.getName(), NodeType.DATABASE, null, false, null, null, null);
            graphRepresentation.addVertex(databaseName);

            currentDatabaseNode = new Node("NodeID" + uniqueID++, NodeType.DATABASE, null, true, databaseName, null, null);
            graphRepresentation.addVertex(currentDatabaseNode);

            graphRepresentation.addEdge(currentDatabaseNode, schemaNode, new LabelEdge("type"));
            graphRepresentation.addEdge(currentDatabaseNode, databaseName, new LabelEdge("name"));
        }

        boolean originalMembership = "ORIGINAL".equals(TABLE_MEMBERSHIP);
        List<Node> membershipTableNodes = new ArrayList<>();
        List<Integer> membershipTableSizes = new ArrayList<>();
        List<Node> membershipColumns = new ArrayList<>();

        for (Table table : db.getTables()) {

            Node currentTableNode = null;
            if (originalMembership || !"COLLAPSED".equals(TABLE_MEMBERSHIP) || membershipTableNodes.isEmpty()) {
            Node tableName = new Node(table.getName(), NodeType.TABLE, null, false, null, null, null);
            graphRepresentation.addVertex(tableName);

            currentTableNode = new Node("NodeID" + uniqueID++, NodeType.TABLE, null, true, tableName, null, null);
            graphRepresentation.addVertex(currentTableNode);

            if (!TABLE_ROOTED) {
                graphRepresentation.addEdge(currentDatabaseNode, currentTableNode, new LabelEdge("table"));
            }
            graphRepresentation.addEdge(currentTableNode, tableNode, new LabelEdge("type"));
            graphRepresentation.addEdge(currentTableNode, tableName, new LabelEdge("name"));
            membershipTableNodes.add(currentTableNode);
            }
            membershipTableSizes.add(table.getColumns().size());

            for (Column column : table.getColumns()) {

                Node columnName = new Node(column.getLabel(), NodeType.COLUMN, column.getDatatype(), false, null, table, column);
                graphRepresentation.addVertex(columnName);

                Node currentColumnNode = new Node("NodeID" + uniqueID++, NodeType.COLUMN, column.getDatatype(), true, columnName, table, null);
                graphRepresentation.addVertex(currentColumnNode);

                if (originalMembership) {
                    graphRepresentation.addEdge(currentTableNode, currentColumnNode, new LabelEdge("column"));
                } else {
                    membershipColumns.add(currentColumnNode);
                }
                graphRepresentation.addEdge(currentColumnNode, columnNode, new LabelEdge("type"));
                graphRepresentation.addEdge(currentColumnNode, columnName, new LabelEdge("name"));

                // TODO 40b diagnostic: datatype is inferred from cell *strings*, so a
                // per-value recoding changes it and the graph gains or loses these
                // nodes. This switch omits them so the effect can be isolated. The
                // default is unchanged production behaviour.
                if (Boolean.getBoolean("smx.sf.noDatatypeNodes")) {
                    continue;
                }

                Node columnDataType = new Node(column.getDatatype().toString(), NodeType.COLUMN_TYPE, column.getDatatype(), false, null, null, null);
                boolean dataTypeNodeExistsInGraph = graphRepresentation.containsVertex(columnDataType);

                if (dataTypeNodeExistsInGraph) { //Edge to existing node

                    Set<LabelEdge> edgesOfIdToColumnType = graphRepresentation.incomingEdgesOf(columnDataType);
                    LabelEdge edgeOfIdToColumnType = edgesOfIdToColumnType.stream().findFirst().orElseThrow(() -> new NoSuchElementException("No such data Type edge present in the graph"));
                    Node columnTypeIdentifier = graphRepresentation.getEdgeSource(edgeOfIdToColumnType);
                    graphRepresentation.addEdge(currentColumnNode, columnTypeIdentifier, new LabelEdge("datatype"));

                } else { //Create new node

                    Node columnTypeIdentifier = new Node("NodeID" + uniqueID++, NodeType.COLUMN_TYPE, column.getDatatype(), true, columnDataType, null, null);

                    graphRepresentation.addVertex(columnDataType);
                    graphRepresentation.addVertex(columnTypeIdentifier);
                    graphRepresentation.addEdge(columnTypeIdentifier, columnDataType, new LabelEdge("name"));
                    graphRepresentation.addEdge(columnTypeIdentifier, columnTypeNode, new LabelEdge("type"));
                    graphRepresentation.addEdge(currentColumnNode, columnTypeIdentifier, new LabelEdge("datatype"));
                }
            }
        }

        if (!originalMembership) {
            attachColumns(db, graphRepresentation, membershipTableNodes, membershipTableSizes, membershipColumns);
        }
    }

    /** T1: the `column` edges under COLLAPSED or PERMUTED membership (see TABLE_MEMBERSHIP). */
    private static void attachColumns(Database db, Graph<Node, LabelEdge> graph, List<Node> tableNodes,
                                      List<Integer> sizes, List<Node> columns) {
        List<Integer> host = new ArrayList<>();
        if ("COLLAPSED".equals(TABLE_MEMBERSHIP)) {
            for (int k = 0; k < columns.size(); k++) {
                host.add(0);
            }
        } else {
            for (int tIdx = 0; tIdx < sizes.size(); tIdx++) {
                for (int k = 0; k < sizes.get(tIdx); k++) {
                    host.add(tIdx);
                }
            }
            Path schema = Paths.get(db.getPath());
            String key = schema.getParent().getParent().getFileName() + "/" + schema.getParent().getFileName()
                    + "/" + schema.getFileName();
            if (TABLE_MEMBERSHIP_DRAW != 0) {
                key = key + "#" + TABLE_MEMBERSHIP_DRAW;
            }
            Collections.shuffle(host, new Random(key.hashCode()));
        }
        StringBuilder assignment = new StringBuilder();
        for (int k = 0; k < columns.size(); k++) {
            graph.addEdge(tableNodes.get(host.get(k)), columns.get(k), new LabelEdge("column"));
            assignment.append(host.get(k)).append(k + 1 < columns.size() ? "," : "");
        }
        System.out.println("TABLEMEMBERSHIP\t" + db.getPath() + "\t" + TABLE_MEMBERSHIP + "\t" + tableNodes.size()
                + " table nodes\t" + assignment);
    }

    private void inclusionDependencies(Database db, Graph<Node, LabelEdge> graphRepresentation, Node constraintNode) {
        List<InclusionDependency> inclusionDependencies = db.getMetadata().getInds().stream().toList();
        int indID = 1;

        for (InclusionDependency inclusionDependency : dependencyFilter.filterInclusionDependencies(inclusionDependencies)) {

            List<Node> dependantIdNodes = new ArrayList<>();
            List<Node> referencedIdNodes = new ArrayList<>();

            for (Column dependant : inclusionDependency.getDependant()) {
                LabelEdge edgeFromIDtoDependant = graphRepresentation.incomingEdgesOf(new Node(dependant.getLabel(), NodeType.COLUMN, dependant.getDatatype(), false, null, dependant.getTable(), null)).stream().findFirst().get();
                Node dependantIDNode = graphRepresentation.getEdgeSource(edgeFromIDtoDependant);
                dependantIdNodes.add(dependantIDNode);
            }

            for (Column referenced : inclusionDependency.getReferenced()) {
                LabelEdge edgeFromIDtoReferenced = graphRepresentation.incomingEdgesOf(new Node(referenced.getLabel(), NodeType.COLUMN, referenced.getDatatype(), false, null, referenced.getTable(), null)).stream().findFirst().get();
                Node referencedIDNode = graphRepresentation.getEdgeSource(edgeFromIDtoReferenced);
                referencedIdNodes.add(referencedIDNode);
            }

            Node indNode = new Node("IND" + indID++, NodeType.CONSTRAINT, null, true, null, null, null);
            graphRepresentation.addVertex(indNode);
            double confidence = dependencyConfidence(inclusionDependency);
            graphRepresentation.addEdge(indNode, constraintNode, new LabelEdge("type", confidence));

            for (Node referencedIDNode : referencedIdNodes) {
                graphRepresentation.addEdge(indNode, referencedIDNode, new LabelEdge("referenced", confidence));
            }

            for (Node dependantIDNode : dependantIdNodes) {
                graphRepresentation.addEdge(indNode, dependantIDNode, new LabelEdge("dependant", confidence));
            }
        }
    }

    @Deprecated
    private void uniqueColumnCombinationsLegacy(Database db, Graph<Node, LabelEdge> graphRepresentation, Node constraintNode) {
        //Legacy way of extending the graph representation with ucc info
        List<UniqueColumnCombination> uniqueColumnCombinations = db.getMetadata().getUccs().stream().toList();
        int uccID = 1;

        for (UniqueColumnCombination ucc : dependencyFilter.filterUniqueColumnCombinations(uniqueColumnCombinations)) {

            Node uccNode = new Node("UCC" + uccID++, NodeType.CONSTRAINT, null, true, null, null, null);
            graphRepresentation.addVertex(uccNode);
            double confidence = dependencyConfidence(ucc);
            graphRepresentation.addEdge(uccNode, constraintNode, new LabelEdge("type", confidence));

            int uccSize = ucc.getColumnCombination().size();
            Node uccSizeNode = new Node("UCC#" + uccSize, NodeType.CONSTRAINT, null, false, null, null, null);

            if (!graphRepresentation.containsVertex(uccSizeNode)) {
                graphRepresentation.addVertex(uccSizeNode);
            }

            graphRepresentation.addEdge(uccNode, uccSizeNode, new LabelEdge("size"));

            List<Node> nodesPartOfUcc = new ArrayList<>();

            for (Column nodePartOfUcc : ucc.getColumnCombination()) {
                LabelEdge edgeFromIDtoUccNode = graphRepresentation.incomingEdgesOf(new Node(nodePartOfUcc.getLabel(), NodeType.COLUMN, nodePartOfUcc.getDatatype(), false, null, nodePartOfUcc.getTable(), null)).stream().findFirst().get();
                Node uccIDNode = graphRepresentation.getEdgeSource(edgeFromIDtoUccNode);
                nodesPartOfUcc.add(uccIDNode);
            }

            for (Node nodePartOfUcc : nodesPartOfUcc) {
                graphRepresentation.addEdge(nodePartOfUcc, uccNode, new LabelEdge("ucc", confidence));
            }
        }
    }

    private void uniqueColumnCombinations(Database db, Graph<Node, LabelEdge> graphRepresentation, Node constraintNode) {
        Collection<UniqueColumnCombination> uniqueColumnCombinations;

        uniqueColumnCombinations = db.getMetadata().getUccs().stream().toList();

        int uccID = 1;

        for (UniqueColumnCombination uniqueColumnCombination : dependencyFilter.filterUniqueColumnCombinations(uniqueColumnCombinations)) {

            Node uccNode = new Node("UCC" + uccID++, NodeType.CONSTRAINT, null, true, null, null, null);
            graphRepresentation.addVertex(uccNode);
            double confidence = dependencyConfidence(uniqueColumnCombination);
            graphRepresentation.addEdge(uccNode, constraintNode, new LabelEdge("type", confidence));

            List<Node> nodesPartOfUcc = new ArrayList<>();

            for(Column columnPartOfUCC : uniqueColumnCombination.getColumnCombination()) {
                LabelEdge edgeFromIDtoColumn = graphRepresentation.incomingEdgesOf(new Node(columnPartOfUCC.getLabel(), NodeType.COLUMN, columnPartOfUCC.getDatatype(), false, null, columnPartOfUCC.getTable(), null)).stream().findFirst().get();
                Node uccColumnIDNode = graphRepresentation.getEdgeSource(edgeFromIDtoColumn);
                nodesPartOfUcc.add(uccColumnIDNode);
            }

            Table tableOfUCC = nodesPartOfUcc.get(0).getNameNode().getRepresentedColumn().getTable();

            List<Node> nodesNotPartOfUCC = new ArrayList<>();

            for(Column columnInTableOfUCC : tableOfUCC.getColumns()) {
                LabelEdge edgeFromIDtoColumn = graphRepresentation.incomingEdgesOf(new Node(columnInTableOfUCC.getLabel(), NodeType.COLUMN, columnInTableOfUCC.getDatatype(), false, null, columnInTableOfUCC.getTable(), null)).stream().findFirst().get();
                Node columnIDNode = graphRepresentation.getEdgeSource(edgeFromIDtoColumn);

                if(!nodesPartOfUcc.contains(columnIDNode)) {
                    nodesNotPartOfUCC.add(columnIDNode);
                }
            }

            for(Node IDNode : nodesPartOfUcc) {
                graphRepresentation.addEdge(uccNode, IDNode, new LabelEdge("unique", confidence));
            }

            // B's dev copy (exploratory X21): -Dsmx.sf.uccNoNotUnique=true drops the notunique edges, so a
            // key node links only to its member columns, as in Melnik et al.'s key representation. Off by default.
            if (!Boolean.getBoolean("smx.sf.uccNoNotUnique")) {
                for(Node IDNode : nodesNotPartOfUCC) {
                    graphRepresentation.addEdge(uccNode, IDNode, new LabelEdge("notunique", confidence));
                }
            }
        }
    }

    private void functionalDependencies(Database db, Graph<Node, LabelEdge> graphRepresentation, Node constraintNode) {
        Collection<FunctionalDependency> functionalDependencies;

        functionalDependencies = db.getMetadata().getMeaningfulFunctionalDependencies(); //only use fds that are not a UCC themselves, as keys are trivial fds

        int fdID = 1;

        for (FunctionalDependency functionalDependency : dependencyFilter.filterFunctionalDependencies(applyAllowList(functionalDependencies, db))) {

            Node fdNode = new Node("FD" + fdID++, NodeType.CONSTRAINT, null, true, null, null, null);
            graphRepresentation.addVertex(fdNode);
            double confidence = dependencyConfidence(functionalDependency);
            graphRepresentation.addEdge(fdNode, constraintNode, new LabelEdge("type", confidence));

            List<Node> determinantIdNodes = new ArrayList<>();

            for (Column determinant : functionalDependency.getDeterminant()) {
                LabelEdge edgeFromIDtoDeterminant = graphRepresentation.incomingEdgesOf(new Node(determinant.getLabel(), NodeType.COLUMN, determinant.getDatatype(), false, null, determinant.getTable(), null)).stream().findFirst().get();
                Node determinantIDNode = graphRepresentation.getEdgeSource(edgeFromIDtoDeterminant);
                determinantIdNodes.add(determinantIDNode);
            }

            Column dependant = functionalDependency.getDependant();

            LabelEdge edgeFromIDtoDependant = graphRepresentation.incomingEdgesOf(new Node(dependant.getLabel(), NodeType.COLUMN, dependant.getDatatype(), false, null, dependant.getTable(), null)).stream().findFirst().get();
            Node dependantIDNode = graphRepresentation.getEdgeSource(edgeFromIDtoDependant);

            for (Node determinantIDNode : determinantIdNodes) {
                graphRepresentation.addEdge(fdNode, determinantIDNode, new LabelEdge("determinant", confidence));
            }

            graphRepresentation.addEdge(fdNode, dependantIDNode, new LabelEdge("dependant", confidence));
        }
    }

    private double dependencyConfidence(FunctionalDependency functionalDependency) {
        return weighFd(functionalDependency);
    }

    private double dependencyConfidence(UniqueColumnCombination uniqueColumnCombination) {
        // B's dev copy (exploratory X16): -Dsmx.sf.uccUnweighted=true keeps UCC edges at weight 1 under every
        // constraintWeighting, so that FD weighting can be tested next to unweighted UCCs. Off by default.
        if (Boolean.getBoolean("smx.sf.uccUnweighted")) {
            return 1.0;
        }
        return weigh(uniqueColumnCombination.getPrimaryKeyScore(), uccRankScores);
    }

    private double dependencyConfidence(InclusionDependency inclusionDependency) {
        return weigh(inclusionDependency.getForeignKeyScore(), indRankScores);
    }

    /**
     * Turn a raw dependency quality score into the edge weight the propagation
     * coefficient is multiplied by. NONE ignores the score entirely, QUALITY
     * passes it through clamped, GATED applies Cupid's three-way rule.
     */
    /**
     * Weight for one functional dependency. Separate from the generic weigh()
     * because TOPK_HYBRID needs the dependency's identity, not just its score.
     */
    private double weighFd(FunctionalDependency fd) {
        if (topkHybridConstraints) {
            return (promoted != null && promoted.contains(fd)) ? topkHigh : topkLow;
        }
        return weigh(fd.calculateNGPDEPScore(), fdRankScores);
    }

    private double weigh(double score, double[] distribution) {
        if (topkHybridConstraints) {
            // UCC and IND edges are not selected by this arm; they take the
            // low weight so the contrast is about functional dependencies only.
            return topkLow;
        }
        if (constantWeightedConstraints) {
            // Deliberately ignores both the score and the distribution.
            return constantWeight;
        }
        if (gatedConstraints) {
            return gate(clampConfidence(score));
        }
        if (rankWeightedConstraints) {
            return rankNormalise(score, distribution);
        }
        return qualityWeightedConstraints ? clampConfidence(score) : 1.0;
    }

    /**
     * Record one score distribution per dependency type for this schema. A
     * no-op unless QUALITY_RANK is active, so the ordinary paths pay nothing.
     *
     * Each type gets its own distribution: a primary-key score must not be
     * ranked against the functional-dependency scores, which is the mistake a
     * single shared array would make.
     */
    private void prepareRankDistributions(Database db) {
        if (topkHybridConstraints) {
            preparePromotedSet(db);
            return;
        }
        if (!rankWeightedConstraints) {
            return;
        }
        this.fdRankScores = sortedScores(
                dependencyFilter.filterFunctionalDependencies(
                        applyAllowList(db.getMetadata().getMeaningfulFunctionalDependencies(), db))
                        .stream().mapToDouble(FunctionalDependency::calculateNGPDEPScore));
        this.uccRankScores = uccThresholdRaw.isBlank() ? null : sortedScores(
                dependencyFilter.filterUniqueColumnCombinations(
                        db.getMetadata().getUccs().stream().toList())
                        .stream().mapToDouble(UniqueColumnCombination::getPrimaryKeyScore));
        this.indRankScores = indThresholdRaw.isBlank() ? null : sortedScores(
                dependencyFilter.filterInclusionDependencies(
                        db.getMetadata().getInds().stream().toList())
                        .stream().mapToDouble(InclusionDependency::getForeignKeyScore));
    }

    /**
     * The top k dependencies of each dependant column, by NGPDEP. Identity
     * based, so two equal-scoring dependencies are distinct members.
     */
    private void preparePromotedSet(Database db) {
        Map<Column, List<FunctionalDependency>> byDependant = new HashMap<>();
        for (FunctionalDependency fd : dependencyFilter.filterFunctionalDependencies(
                applyAllowList(db.getMetadata().getMeaningfulFunctionalDependencies(), db))) {
            byDependant.computeIfAbsent(fd.getDependant(), c -> new ArrayList<>()).add(fd);
        }
        java.util.Set<FunctionalDependency> chosen =
                java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        int total = 0;
        for (List<FunctionalDependency> group : byDependant.values()) {
            group.sort(Comparator.comparingDouble(
                    FunctionalDependency::calculateNGPDEPScore).reversed());
            // A fixed k treats a column with three dependencies the same as one
            // with sixty. A percentage scales with how much competition the
            // column actually has, with a floor of one so no column is left
            // without its best dependency.
            int take = topKPercent > 0
                    ? Math.max(1, (int) Math.ceil(group.size() * topKPercent / 100.0))
                    : topK;
            for (int i = 0; i < Math.min(take, group.size()); i++) {
                chosen.add(group.get(i));
            }
            total += group.size();
        }
        this.promoted = chosen;
        log.info("TOPK_HYBRID: promoted {} of {} FDs over {} dependant columns (k={}, pct={})",
                chosen.size(), total, byDependant.size(), topK, topKPercent);
    }

    private static double[] sortedScores(java.util.stream.DoubleStream scores) {
        double[] sorted = scores.filter(s -> !Double.isNaN(s)).sorted().toArray();
        return sorted.length > 0 ? sorted : null;
    }

    /**
     * The share of dependencies scoring no higher than this one, so the
     * strongest present weighs exactly 1.0. Ties share the higher rank, which
     * is the conservative direction: a dependency tied with the best is treated
     * as the best rather than discounted for having company.
     */
    private double rankNormalise(double score, double[] distribution) {
        if (distribution == null || distribution.length <= 1 || Double.isNaN(score)) {
            // No distribution recorded, a single dependency, or an unscored one:
            // fall back to NONE rather than silently weighting it to zero.
            return 1.0;
        }
        int hi = java.util.Arrays.binarySearch(distribution, score);
        if (hi < 0) {
            hi = -(hi + 1);
        } else {
            while (hi + 1 < distribution.length && distribution[hi + 1] == score) {
                hi++;
            }
            hi++;
        }
        return clampConfidence((double) hi / distribution.length);
    }

    private double gate(double score) {
        double anchor = Math.max(gateIncrease, 1.0);
        if (score > gateHigh) {
            return gateIncrease / anchor;
        }
        if (score < gateLow) {
            return gateDecrease / anchor;
        }
        return 1.0 / anchor;
    }

    private double clampConfidence(double confidence) {
        if (Double.isNaN(confidence)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, confidence));
    }

}
