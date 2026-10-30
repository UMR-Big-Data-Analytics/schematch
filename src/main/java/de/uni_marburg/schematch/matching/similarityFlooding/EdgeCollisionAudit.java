package de.uni_marburg.schematch.matching.similarityFlooding;

import org.jgrapht.graph.DefaultDirectedWeightedGraph;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Counts the graph insertions that are silently discarded.
 *
 * All three stages of {@link SchemaGraphBuilder} store their result in a
 * {@link DefaultDirectedWeightedGraph}, and no call site checks what
 * {@code addEdge} returned. JGraphT refuses an insertion for two unrelated
 * reasons, which this audit keeps apart because they are different defects
 * with different fixes.
 *
 * <p><b>Parallel.</b> The container holds at most one edge between an ordered
 * pair of vertices, so a second contribution between the same endpoints is
 * dropped rather than combined, and the surviving edge is whichever one was
 * offered first. What that costs depends on what collided:
 *
 * <ul>
 *   <li><em>same label, same weight</em> -- a repeated encoding of the same
 *       evidence, e.g. one column pair projected out of several functional
 *       dependencies under the Quick encoding. Discarding it is a defensible
 *       aggregation choice, not a loss of information.</li>
 *   <li><em>same label, different weight</em> -- the retained weight is decided
 *       by iteration order. Under {@code constraintWeighting=NONE} every
 *       dependency weighs 1.0 and this cannot happen at the schema stage;
 *       under QUALITY, QUALITY_RANK and GATED it can, and the edge then
 *       carries an arbitrary one of the competing confidences. Propagation
 *       coefficients differ by construction, since the forward one is
 *       normalised by outgoing degrees and the reverse one by incoming.</li>
 *   <li><em>different label</em> -- a whole propagation channel is lost,
 *       because the two contributions are not the same evidence at all.</li>
 * </ul>
 *
 * <p><b>Duplicate object.</b> {@code AbstractBaseGraph.addEdge} first tests
 * {@code containsEdge(e)}, a lookup of the edge object in a hash map. Both
 * {@link LabelEdge} and {@link CoefficientEdge} override {@code equals} --
 * by label and by coefficient respectively -- without overriding
 * {@code hashCode}, so an equal edge is found only when its identity hash
 * happens to fall in the same bucket. When it does, the insertion is refused
 * although the endpoints are free, and the edge is lost entirely rather than
 * merged with anything. Identity hashes vary between JVM runs, so this cause
 * makes the graph differ between runs on identical input.
 *
 * The audit is off unless {@code -Dsmx.sf.auditEdgeCollisions=true} or the
 * existing {@code -Dsmx.sf.debugGraph=true} is set, and when it is off the
 * graphs are the plain container and nothing here is consulted. It counts and
 * reports only, so an audited run must produce byte-identical scores to an
 * ordinary one.
 *
 * The counters are static and this class is not thread-safe by design. It is
 * meant for the one-scenario-per-JVM bridge (harness/java/AuditedPmfSf.java),
 * where a single match task builds the graphs on one thread. Enabling it in a
 * parallel Schematch run would pool several match tasks' counts.
 */
final class EdgeCollisionAudit {

    static final boolean ENABLED =
            Boolean.getBoolean("smx.sf.auditEdgeCollisions") || Boolean.getBoolean("smx.sf.debugGraph");

    /** One construction stage and one relation label. */
    private record Bucket(String stage, String label) {
    }

    /** Insertion outcomes for one (stage, label) pair. */
    private static final class Tally {
        long attempts;
        long rejected;
        long parallelSameLabel;
        long parallelOtherLabel;
        long duplicateObject;
        long weightConflicts;
        double maxWeightDelta;
        /** Which label held the slot, when a parallel rejection lost to another one. */
        final Map<String, Long> displacedBy = new TreeMap<>();
    }

    /**
     * The audit's mutable state, allocated on first use rather than on class
     * initialisation.
     *
     * A nested class is initialised when it is first touched, and every access
     * below sits behind the {@link #ENABLED} check, so a run that does not set
     * the property allocates nothing here. That matters more than it looks:
     * the defect this class measures is decided by identity hashes, and
     * identity hashes are handed out in the order objects are first hashed, so
     * instrumentation that allocates during graph construction can change what
     * it is trying to observe. Keeping the ordinary path allocation-free
     * removes the audit as a variable.
     *
     * PROPAGATION_LABELS holds the label of each propagation edge already
     * inserted, by endpoint pair: the propagation graph's edges carry a
     * coefficient and nothing else, so a refused insertion cannot be asked what
     * the edge occupying that slot stands for, and without it every propagation
     * collision would be booked as a repeated encoding of the same relation.
     */
    private static final class State {
        static final Map<Bucket, Tally> TALLIES = new LinkedHashMap<>();
        static final Map<EndpointPair, String> PROPAGATION_LABELS = new HashMap<>();
    }

    /** An ordered pair of propagation-graph vertices. */
    private record EndpointPair(Object source, Object target) {
    }

    private EdgeCollisionAudit() {
    }

    /** A graph that reports its refused insertions instead of swallowing them. */
    static final class AuditedGraph<V, E> extends DefaultDirectedWeightedGraph<V, E> {

        private final String stage;

        AuditedGraph(Class<? extends E> edgeClass, String stage) {
            super(edgeClass);
            this.stage = stage;
        }

        @Override
        public boolean addEdge(V source, V target, E edge) {
            boolean inserted = super.addEdge(source, target, edge);
            if (inserted) {
                record(stage, labelOf(edge), true, false, null, 0.0, 0.0);
                return true;
            }
            // An occupied slot is the parallel-edge rule; a free one means the
            // edge object itself was taken for one already in the graph.
            E holder = getEdge(source, target);
            record(stage, labelOf(edge), false, holder != null, labelOf(holder),
                    weightOf(holder), weightOf(edge));
            return false;
        }

        private static String labelOf(Object edge) {
            return edge instanceof LabelEdge labelEdge ? labelEdge.getLabel() : "";
        }

        private static double weightOf(Object edge) {
            if (edge instanceof LabelEdge labelEdge) {
                return labelEdge.getWeight();
            }
            if (edge instanceof CoefficientEdge coefficientEdge) {
                return coefficientEdge.getCoefficient();
            }
            return Double.NaN;
        }
    }

    /**
     * The container for one construction stage: audited when the flag is set,
     * the plain JGraphT container otherwise.
     */
    static <V, E> DefaultDirectedWeightedGraph<V, E> newGraph(Class<? extends E> edgeClass, String stage) {
        return ENABLED ? new AuditedGraph<>(edgeClass, stage)
                : new DefaultDirectedWeightedGraph<>(edgeClass);
    }

    /**
     * Records one propagation-stage insertion.
     *
     * The propagation graph's edges carry a coefficient and no label, and the
     * two call sites in {@link SchemaGraphBuilder#inducePropagationGraph} mean
     * different things -- one projects a connectivity edge forwards, the other
     * adds the reverse message Similarity Flooding needs. The label, that
     * direction, and whether the endpoints were already occupied therefore
     * come from the caller rather than off the edge.
     */
    static void propagationEdge(String direction, String label, Object source, Object target,
                                boolean inserted, boolean slotOccupied,
                                double existingCoefficient, double attemptedCoefficient) {
        EndpointPair endpoints = new EndpointPair(source, target);
        if (inserted) {
            State.PROPAGATION_LABELS.put(endpoints, label);
            record("propagation_" + direction, label, true, false, null, 0.0, 0.0);
            return;
        }
        record("propagation_" + direction, label, false, slotOccupied,
                slotOccupied ? State.PROPAGATION_LABELS.get(endpoints) : null,
                existingCoefficient, attemptedCoefficient);
    }

    private static void record(String stage, String label, boolean inserted, boolean slotOccupied,
                               String existingLabel, double existingWeight, double attemptedWeight) {
        Tally tally = State.TALLIES.computeIfAbsent(new Bucket(stage, label), key -> new Tally());
        tally.attempts++;
        if (inserted) {
            return;
        }
        tally.rejected++;
        if (!slotOccupied) {
            tally.duplicateObject++;
            return;
        }
        if (existingLabel != null && existingLabel.equals(label)) {
            tally.parallelSameLabel++;
            double delta = Math.abs(existingWeight - attemptedWeight);
            if (delta > 0.0) {
                tally.weightConflicts++;
                tally.maxWeightDelta = Math.max(tally.maxWeightDelta, delta);
            }
        } else {
            tally.parallelOtherLabel++;
            tally.displacedBy.merge(existingLabel == null ? "?" : existingLabel, 1L, Long::sum);
        }
    }

    /** Prints one line per (stage, label) and clears the counters. */
    static void report() {
        if (!ENABLED) {
            return;
        }
        for (Map.Entry<Bucket, Tally> entry : State.TALLIES.entrySet()) {
            Bucket bucket = entry.getKey();
            Tally tally = entry.getValue();
            System.out.println("EDGEAUDIT stage=" + bucket.stage()
                    + " label=" + (bucket.label().isEmpty() ? "-" : bucket.label())
                    + " attempts=" + tally.attempts
                    + " rejected=" + tally.rejected
                    + " parallel_same_label=" + tally.parallelSameLabel
                    + " parallel_other_label=" + tally.parallelOtherLabel
                    + " duplicate_object=" + tally.duplicateObject
                    + " weight_conflicts=" + tally.weightConflicts
                    + " max_weight_delta=" + tally.maxWeightDelta
                    + " displaced_by=" + (tally.displacedBy.isEmpty() ? "-" : tally.displacedBy));
        }
        State.TALLIES.clear();
        State.PROPAGATION_LABELS.clear();
    }
}
