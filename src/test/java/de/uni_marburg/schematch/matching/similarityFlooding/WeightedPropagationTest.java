package de.uni_marburg.schematch.matching.similarityFlooding;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WeightedPropagationTest {

    @Test
    void dependencyEdgeWeightsDampenPropagationCoefficients() {
        Graph<Node, LabelEdge> graph1 = new DefaultDirectedWeightedGraph<>(LabelEdge.class);
        Node a = new Node("a", null, null, false, null, null, null);
        Node a1 = new Node("a1", null, null, false, null, null, null);
        graph1.addVertex(a);
        graph1.addVertex(a1);
        graph1.addEdge(a, a1, new LabelEdge("determinant", 0.4));

        Graph<Node, LabelEdge> graph2 = new DefaultDirectedWeightedGraph<>(LabelEdge.class);
        Node b = new Node("b", null, null, false, null, null, null);
        Node b1 = new Node("b1", null, null, false, null, null, null);
        graph2.addVertex(b);
        graph2.addVertex(b1);
        graph2.addEdge(b, b1, new LabelEdge("determinant", 0.8));

        Graph<NodePair, LabelEdge> connectivityGraph = SchemaGraphBuilder.createConnectivityGraph(graph1, graph2);
        NodePair sourcePair = new NodePair(a, b);
        NodePair targetPair = new NodePair(a1, b1);

        LabelEdge connectivityEdge = connectivityGraph.getEdge(sourcePair, targetPair);
        Assertions.assertEquals(0.4, connectivityEdge.getWeight());

        Graph<NodePair, CoefficientEdge> propagationGraph = SchemaGraphBuilder.inducePropagationGraph(
                connectivityGraph,
                graph1,
                graph2,
                PropagationCoefficientPolicy.INVERSE_PRODUCT
        );

        Assertions.assertEquals(0.4, propagationGraph.getEdge(sourcePair, targetPair).getCoefficient());
        Assertions.assertEquals(0.4, propagationGraph.getEdge(targetPair, sourcePair).getCoefficient());
    }
}
