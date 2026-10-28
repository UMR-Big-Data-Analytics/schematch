package de.uni_marburg.schematch.matching.similarityFlooding;
import org.jgrapht.Graph;
import java.util.Map;
import java.util.Set;

public enum FixpointFormula {

    BASIC(Base.SIGMA_I, Value.SIGMA_I, false) {
        @Override
        public double evaluate(NodePair node, Set<NodePair> neighborNodes, Map<NodePair, Double> sigma_0, Map<NodePair, Double> sigma_i, Graph<NodePair, CoefficientEdge> propagationGraph) {

            double phi = 0;

            for (NodePair neighbor : neighborNodes) {
                double neighborValue = sigma_i.get(neighbor);

                double propagationCoefficient;
                try{
                    propagationCoefficient = propagationGraph.getEdge(neighbor, node).getCoefficient();
                } catch(Exception e) {
                    propagationCoefficient = 0.0;
                }

                phi += neighborValue * propagationCoefficient;
            }

            return sigma_i.get(node) + phi;
        }
    },

    BASIC_Lambda(Base.SIGMA_I, Value.SIGMA_I, true) {
        @Override
        public double evaluate(NodePair node, Set<NodePair> neighborNodes, Map<NodePair, Double> sigma_0, Map<NodePair, Double> sigma_i, Graph<NodePair, CoefficientEdge> propagationGraph) {

            double phi = 0;

            for (NodePair neighbor : neighborNodes) {
                double neighborValue = sigma_i.get(neighbor);
                double propagationCoefficient;

                try{
                    propagationCoefficient = propagationGraph.getEdge(neighbor, node).getCoefficient();
                } catch(Exception e) {
                    propagationCoefficient = 0.0;
                }

                if(neighbor.getFirstNode().getNodeType().equals(NodeType.CONSTRAINT) && neighbor.getSecondNode().getNodeType().equals(NodeType.CONSTRAINT)) {
                    phi += LAMBDA_D * propagationCoefficient * neighborValue;
                } else {
                    phi += LAMBDA_E * propagationCoefficient * neighborValue;
                }
            }

            return sigma_i.get(node) + phi;
        }
    },

    FORMULA_A(Base.SIGMA_0, Value.SIGMA_I, false) {
        @Override
        public double evaluate(NodePair node, Set<NodePair> neighborNodes, Map<NodePair, Double> sigma_0, Map<NodePair, Double> sigma_i, Graph<NodePair, CoefficientEdge> propagationGraph) {

            double phi = 0;

            for (NodePair neighbor : neighborNodes) {
                double neighborValue = sigma_i.get(neighbor);
                double propagationCoefficient;

                try{
                    propagationCoefficient = propagationGraph.getEdge(neighbor, node).getCoefficient();
                } catch(Exception e) {
                    propagationCoefficient = 0.0;
                }

                phi += neighborValue * propagationCoefficient;
            }

            return sigma_0.get(node) + phi;
        }
    },

    // Weighted variants for the Fixpoint Formulas

    FORMULA_A_Lambda(Base.SIGMA_0, Value.SIGMA_I, true) {
        @Override
        public double evaluate(NodePair node, Set<NodePair> neighborNodes, Map<NodePair, Double> sigma_0, Map<NodePair, Double> sigma_i, Graph<NodePair, CoefficientEdge> propagationGraph) {

            double phi = 0;

            for (NodePair neighbor : neighborNodes) {
                double neighborValue = sigma_i.get(neighbor);
                double propagationCoefficient;

                try{
                    propagationCoefficient = propagationGraph.getEdge(neighbor, node).getCoefficient();
                } catch(Exception e) {
                    propagationCoefficient = 0.0;
                }

                if(neighbor.getFirstNode().getNodeType().equals(NodeType.CONSTRAINT) && neighbor.getSecondNode().getNodeType().equals(NodeType.CONSTRAINT)) {
                    phi += LAMBDA_D * propagationCoefficient * neighborValue;
                } else {
                    phi += LAMBDA_E * propagationCoefficient * neighborValue;
                }
            }

            return sigma_0.get(node) + phi;
        }
    },

    FORMULA_B(Base.ZERO, Value.SIGMA_0_PLUS_I, false) {
        @Override
        public double evaluate(NodePair node, Set<NodePair> neighborNodes, Map<NodePair, Double> sigma_0, Map<NodePair, Double> sigma_i, Graph<NodePair, CoefficientEdge> propagationGraph) {

            double phi = 0;

            for (NodePair neighbor : neighborNodes) {
                double neighborValue_0 = sigma_0.get(neighbor);
                double neighborValue_i = sigma_i.get(neighbor);
                double propagationCoefficient;

                try{
                    propagationCoefficient = propagationGraph.getEdge(neighbor, node).getCoefficient();
                } catch(Exception e) {
                    propagationCoefficient = 0.0;
                }

                phi += (neighborValue_0 + neighborValue_i) * propagationCoefficient;
            }

            return phi;
        }
    },

    FORMULA_B_Lambda(Base.ZERO, Value.SIGMA_0_PLUS_I, true) {
        @Override
        public double evaluate(NodePair node, Set<NodePair> neighborNodes, Map<NodePair, Double> sigma_0, Map<NodePair, Double> sigma_i, Graph<NodePair, CoefficientEdge> propagationGraph) {

            double phi = 0;

            for (NodePair neighbor : neighborNodes) {
                double neighborValue_0 = sigma_0.get(neighbor);
                double neighborValue_i = sigma_i.get(neighbor);
                double propagationCoefficient;

                try{
                    propagationCoefficient = propagationGraph.getEdge(neighbor, node).getCoefficient();
                } catch(Exception e) {
                    propagationCoefficient = 0.0;
                }

                if(neighbor.getFirstNode().getNodeType().equals(NodeType.CONSTRAINT) && neighbor.getSecondNode().getNodeType().equals(NodeType.CONSTRAINT)) {
                    phi += LAMBDA_D * (neighborValue_0 + neighborValue_i) * propagationCoefficient;
                } else {
                    phi += LAMBDA_E * (neighborValue_0 + neighborValue_i) * propagationCoefficient;
                }
            }

            return phi;
        }
    },

    FORMULA_C(Base.SIGMA_0_PLUS_I, Value.SIGMA_0_PLUS_I, false) {
        @Override
        public double evaluate(NodePair node, Set<NodePair> neighborNodes, Map<NodePair, Double> sigma_0, Map<NodePair, Double> sigma_i, Graph<NodePair, CoefficientEdge> propagationGraph) {

            double phi = 0;

            for (NodePair neighbor : neighborNodes) {

                double neighborValue_0 = sigma_0.get(neighbor);
                double neighborValue_i = sigma_i.get(neighbor);

                double propagationCoefficient;
                try {
                    propagationCoefficient = propagationGraph.getEdge(neighbor, node).getCoefficient();
                } catch (Exception e) {
                    propagationCoefficient = 0.0;
                }

                phi += (neighborValue_0 + neighborValue_i) * propagationCoefficient;
            }

            return sigma_0.get(node) + sigma_i.get(node) + phi;
        }
    },

    FORMULA_C_Lambda(Base.SIGMA_0_PLUS_I, Value.SIGMA_0_PLUS_I, true) {

        @Override
        public double evaluate(NodePair node, Set<NodePair> neighborNodes, Map<NodePair, Double> sigma_0, Map<NodePair, Double> sigma_i, Graph<NodePair, CoefficientEdge> propagationGraph) {

            double phi = 0;

            for (NodePair neighbor : neighborNodes) {
                double neighborValue_0 = sigma_0.get(neighbor);
                double neighborValue_i = sigma_i.get(neighbor);
                double propagationCoefficient;

                try {
                    propagationCoefficient = propagationGraph.getEdge(neighbor, node).getCoefficient();
                } catch (Exception e) {
                    propagationCoefficient = 0.0;
                }

                if(neighbor.getFirstNode().getNodeType().equals(NodeType.CONSTRAINT) && neighbor.getSecondNode().getNodeType().equals(NodeType.CONSTRAINT)) {
                    phi += LAMBDA_D * (neighborValue_0 + neighborValue_i) * propagationCoefficient;
                } else {
                    phi += LAMBDA_E * (neighborValue_0 + neighborValue_i) * propagationCoefficient;
                }
            }

            return sigma_0.get(node) + sigma_i.get(node) + phi;
        }
    };

    protected static final double LAMBDA_D = 1.0;
    protected static final double LAMBDA_E = 1.0;

    /** What the formula adds to the propagated mass. */
    enum Base { ZERO, SIGMA_I, SIGMA_0, SIGMA_0_PLUS_I }

    /** Which neighbour value the formula propagates. */
    enum Value { SIGMA_I, SIGMA_0_PLUS_I }

    private final Base base;
    private final Value value;
    private final boolean weighted;

    FixpointFormula(Base base, Value value, boolean weighted) {
        this.base = base;
        this.value = value;
        this.weighted = weighted;
    }

    /**
     * Array-based form of the fixpoint step, used by the iteration loop.
     *
     * The map-based {@code evaluate} above resolves every neighbour through a
     * {@code HashMap<NodePair, Double>} and a graph edge lookup, which
     * dominates the runtime once the propagation graph is large. Here the node
     * pairs are integers and the incoming edges are a CSR adjacency, so a step
     * is a flat loop over primitives.
     *
     * The eight formulas differ only in which neighbour value they propagate
     * and what they add to the result, so they share one implementation
     * parameterised by those two choices rather than repeating the loop.
     *
     * @param node               index of the map pair being updated
     * @param rowPtr             CSR offsets, length n+1
     * @param src                source index of each incoming edge
     * @param coeff              propagation coefficient of each incoming edge
     * @param isDependencyPair   whether a map pair joins two constraint nodes,
     *                           which is what the lambda variants weight
     */
    public double evaluate(int node, double[] sigma_0, double[] sigma_i,
                           int[] rowPtr, int[] src, double[] coeff,
                           boolean[] isDependencyPair) {

        double phi = 0.0;
        int end = rowPtr[node + 1];

        for (int k = rowPtr[node]; k < end; k++) {
            int neighbor = src[k];
            double neighborValue = (this.value == Value.SIGMA_I)
                    ? sigma_i[neighbor]
                    : sigma_0[neighbor] + sigma_i[neighbor];

            double propagationCoefficient = coeff[k];
            if (this.weighted) {
                propagationCoefficient *= isDependencyPair[neighbor] ? LAMBDA_D : LAMBDA_E;
            }

            phi += neighborValue * propagationCoefficient;
        }

        return switch (this.base) {
            case ZERO -> phi;
            case SIGMA_I -> sigma_i[node] + phi;
            case SIGMA_0 -> sigma_0[node] + phi;
            case SIGMA_0_PLUS_I -> sigma_0[node] + sigma_i[node] + phi;
        };
    }

    public double evaluate(NodePair node, Set<NodePair> neighborNodes, Map<NodePair, Double> sigma_0, Map<NodePair, Double> sigma_i, Graph<NodePair, CoefficientEdge> propagationGraph) throws NoSuchMethodException {
        throw new NoSuchMethodException("Not implemented");
    }

}
