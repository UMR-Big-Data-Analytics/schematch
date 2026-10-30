package de.uni_marburg.schematch.matching.similarityFlooding;

import lombok.Getter;

@Getter
public class LabelEdge {

    private final String label;
    private final double weight;

    public LabelEdge(String label) {
        this(label, 1.0);
    }

    public LabelEdge(String label, double weight) {
        if (weight < 0 || weight > 1) {
            throw new IllegalArgumentException("weight must be between 0 and 1");
        }
        this.label = label;
        this.weight = weight;
    }

    @Override
    public String toString() {
        return label;
    }

    /**
     * TODO: restore the edge-identity contract JGraphT relies on.
     *
     * The {@code equals} below makes two edge objects equal when they carry the
     * same label, and no {@code hashCode} accompanies it. JGraphT stores
     * edges as keys of a hash map and {@code AbstractBaseGraph.addEdge} tests
     * {@code containsEdge(e)} before anything else, so an equal edge is found
     * only when its identity hash happens to land in the same bucket -- and
     * then the insertion is refused although its endpoints are free, losing the
     * edge outright. `harness/sf_edge_collision_audit.py` measures 4,482 edges
     * lost this way over the corpus, 4,245 of them in OpenData.
     *
     * The repair is to drop the content-based equality, not to add a matching
     * {@code hashCode}. A consistent content hash would make {@code
     * containsEdge} succeed for *every* subsequent edge carrying the same
     * label, so the graph would accept one edge per distinct label and
     * discard the rest -- far worse than the bug. JGraphT's own DefaultEdge and
     * DefaultWeightedEdge override neither method for exactly this reason: a
     * graph holds many distinct edge objects that are alike, and only identity
     * can tell them apart. `harness/java/EqualsHashProbe.java` runs all three
     * variants side by side.
     *
     * **On by default since 2026-09-17.** The reason is reproducibility rather
     * than accuracy: without it the graph depends on identity hashes, so the
     * same schemas can produce different scores across builds or JVMs, and 15
     * of 246 re-run OpenData cells did. With it the operator is a function of
     * its input. Accuracy is indifferent -- 764 cells, both regimes, every
     * cluster-bootstrap interval includes zero, and pessimistic Recall@GT does
     * not move on a single cell (SF_EDGE_IDENTITY_REPAIR.md).
     *
     * It does change outputs: 252 of 764 score matrices and 43 F1 values. Set
     * -Dsmx.sf.consistentEdgeIdentity=false to restore the historical path,
     * which is what reproducing an artifact produced before this date requires.
     */
    private static final boolean IDENTITY_EQUALITY =
            Boolean.parseBoolean(System.getProperty("smx.sf.consistentEdgeIdentity", "true"));

    @Override
    public boolean equals(Object other) {
        if (IDENTITY_EQUALITY) {
            return this == other;
        }
        if (other instanceof LabelEdge otherlabelEdge) { //Equality holds between two Objects if both are of class LabelEdge
            return this.label.equals(otherlabelEdge.label); //And both hold the equal String value
        }
        return false;
    }

}
