package de.uni_marburg.schematch.matching.similarityFlooding;

public class NodePair {

    private final Node node1;
    private final Node node2;

    public NodePair(Node node1, Node node2) {
        this.node1 = node1;
        this.node2 = node2;
    }

    public Node getFirstNode() {
        return node1;
    }

    public Node getSecondNode() {
        return node2;
    }

    /**
     * TODO 40c, second repair: treat a map pair as directional.
     *
     * The clause below is order-insensitive while {@link #hashCode} is not, and
     * the pair is only ever built from one node of each schema graph, so the
     * mirror image is a different correspondence rather than the same one. This
     * switch restores the contract from the equality side instead of the hash
     * side, keeping (a,b) and (b,a) distinct. Default behaviour is unchanged.
     */
    private static final boolean DIRECTED =
            Boolean.getBoolean("smx.sf.directedNodePairs");

    //Equal if NodePair is a tuple of the same nodes, order does not matter
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof NodePair other) {
            if (DIRECTED) {
                return other.node1.equals(this.node1) && other.node2.equals(this.node2);
            }
            return (other.node1.equals(this.node1) && other.node2.equals(this.node2)) || (other.node1.equals(this.node2) && other.node2.equals(this.node1));
        }
        return false;
    }

    /**
     * TODO 40c intervention: make the hash agree with {@link #equals}.
     *
     * {@code equals} above is deliberately order-insensitive, but the default
     * hash below is order-sensitive, so {@code (a,b)} and {@code (b,a)} are
     * equal with different hash codes. That breaks the {@code equals}/
     * {@code hashCode} contract: in a hash map the two land in different
     * buckets unless their codes happen to collide modulo the table size, so
     * whether the pair is deduplicated depends on a bucket coincidence -- and
     * the codes are built from {@code Node.hashCode()}, which is built from the
     * column label. Relabelling a schema can therefore change how many vertices
     * and edges the propagation graph has, which is why an exactly isomorphic
     * pair of schemas does not always flood to the same fixpoint.
     *
     * Symmetric pairs are not hypothetical here: the schema graphs of the two
     * databases both contain nodes named {@code NodeID1..k} and the shared
     * database-name node, and {@code Node.equals} compares values alone when
     * either table is null.
     *
     * The default path is unchanged.
     */
    private static final boolean CONSISTENT_HASH =
            Boolean.getBoolean("smx.sf.consistentNodePairHash");

    @Override
    public int hashCode() {
        if (CONSISTENT_HASH && !DIRECTED) {
            return (node1 == null ? 0 : node1.hashCode())
                    + (node2 == null ? 0 : node2.hashCode());
        }
        int prime = 31;
        int result = 1;
        result = prime * result + (node1 == null ? 0 : node1.hashCode());
        result = prime * result + (node2 == null ? 0 : node2.hashCode());
        return result;
    }

    @Override
    public String toString() {
        return "(" + node1.toString() + ", " + node2.toString() + ")";
    }

}
