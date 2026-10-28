package de.uni_marburg.schematch.matching.similarityFlooding;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Table;

import java.util.*;

public class SimilarityFloodingUtils {

    public static double calcResidualVector(Map<NodePair, Double> sigma_i, Map<NodePair, Double> sigma_i_plus_1) {

        double residualSum = 0;

        for (NodePair node : sigma_i.keySet()) {

            double value_i = sigma_i.get(node);
            double value_i_plus_1 = sigma_i_plus_1.get(node);

            residualSum = residualSum + Math.pow((value_i - value_i_plus_1), 2);
        }

        return Math.sqrt(residualSum);
    }

    public static boolean hasConverged(Map<NodePair, Double> sigma_i, Map<NodePair, Double> sigma_i_plus_1, double epsilon) {
        return calcResidualVector(sigma_i, sigma_i_plus_1) < epsilon;
    }

    /** Array form of the residual, for the indexed fixpoint iteration. */
    public static double calcResidualVector(double[] sigma_i, double[] sigma_i_plus_1) {

        double residualSum = 0;

        for (int i = 0; i < sigma_i.length; i++) {
            double difference = sigma_i[i] - sigma_i_plus_1[i];
            residualSum = residualSum + difference * difference;
        }

        return Math.sqrt(residualSum);
    }

    public static boolean hasConverged(double[] sigma_i, double[] sigma_i_plus_1, double epsilon) {
        return calcResidualVector(sigma_i, sigma_i_plus_1) < epsilon;
    }


    public static void populateSimMatrix(float[][] simMatrix, Map<NodePair, Double> mapping, Table sourceTable, Table targetTable) {

        List<Column> sourceColumns = sourceTable.getColumns();
        List<Column> targetColumns = targetTable.getColumns();

        for (int i = 0; i < sourceColumns.size(); i++) {

            String sourceLabel = sourceColumns.get(i).getLabel();
            Node sourceNode = new Node(sourceLabel, NodeType.COLUMN, null, false, null, sourceTable, null);

            for (int j = 0; j < targetColumns.size(); j++) {

                String targetLabel = targetColumns.get(j).getLabel();
                Node targetNode = new Node(targetLabel, NodeType.COLUMN, null, false, null, targetTable, null);

                float similarity = mapping.getOrDefault(new NodePair(sourceNode, targetNode), 0.0).floatValue();

                simMatrix[sourceTable.getOffset() + i][targetTable.getOffset() + j] = similarity;
            }
        }
    }

    static double getValueSimilarityBetweenColumns(Column column1, Column column2) {
        Map<String, Double> probabilityMap1 = getValueProbabilities(column1);
        Map<String, Double> probabilityMap2 = getValueProbabilities(column2);

        double distance = getDistanceBetweenProbabilityMaps(probabilityMap1, probabilityMap2);

        return 1.0 / (1.0 + distance);
    }

    private static Map<String, Double> getValueProbabilities(Column column) {
        Map<String, Integer> countMap = new HashMap<>();
        int total = 0;

        for (String value : column.getValues()) {
            if (!value.equalsIgnoreCase("null")) { //Ignore null values
                countMap.put(value, countMap.getOrDefault(value, 0) + 1);
                total++;
            }
        }

        Map<String, Double> probabilityMap = new HashMap<>();

        for (Map.Entry<String, Integer> entry : countMap.entrySet()) {
            probabilityMap.put(entry.getKey(), entry.getValue() / (double) total);
        }

        return probabilityMap;
    }

    //Only keep matching between elements of same kind, put simValue of IDNodes with their nameValues
    static Map<NodePair, Double> filterMapping(Map<NodePair, Double> mapping) {

        Map<NodePair, Double> filteredMapping = new HashMap<>();

        for (Map.Entry<NodePair, Double> entry : mapping.entrySet()) {

            Node node1 = entry.getKey().getFirstNode();
            Node node2 = entry.getKey().getSecondNode();
            Double simValue = entry.getValue();

            if (node1.isIDNode() && node2.isIDNode()) {

                NodePair pair = new NodePair(node1.getNameNode(), node2.getNameNode());

                //Only keep matches between Columns
                if (node1.getNodeType().equals(node2.getNodeType())) {
                    if (node1.getNodeType().equals(NodeType.COLUMN)) {
                        filteredMapping.put(pair, simValue);
                    }
                }
            }
        }
        return filteredMapping;
    }

    private static double getDistanceBetweenProbabilityMaps(Map<String, Double> map1, Map<String, Double> map2) {

        double distance = 0.0;

        Set<String> possibleValues = new HashSet<>(map1.keySet());
        possibleValues.addAll(map2.keySet());

        for (String value : possibleValues) {
            Double probability1 = map1.getOrDefault(value, 0.0);
            Double probability2 = map2.getOrDefault(value, 0.0);

            distance += Math.pow(probability1 - probability2, 2);
        }
        return distance;
    }

}
