package de.uni_marburg.schematch.data.metadata.dependency;

import de.uni_marburg.schematch.data.Column;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import java.util.*;

@Data
public class FunctionalDependency implements Dependency {
    Collection<Column> determinant;
    Column dependant;

    // gpdep depends only on the column values, which do not change once the
    // database is read, but calculateNGPDEPScore recomputes the score of every
    // FD sharing its dependent in order to normalise. Without memoisation that
    // makes scoring quadratic in the number of FDs per dependent.
    // Excluded from equals/hashCode: FDs live in hash sets, and a derived
    // score must not affect their identity.
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private Double gpdepScoreCache;

    //{left1, left2,...} -> {right}
    public FunctionalDependency(Collection<Column> left, Column right) {
        this.determinant = left;
        this.dependant = right;
    }

    @Override
    public String toString() {
        String left = determinant.isEmpty() ? "[]" : Util.columnsToString(determinant);
        String right = Util.columnToString(dependant);
        return left + " --> " + right;
    }

    private List<String> getRowsAsStrings(Collection<Column> columns, int rowCount) {

        List<String> rowsAsString = new ArrayList<>(rowCount);
        StringBuilder stringBuilder = new StringBuilder();

        for (int i = 0; i < rowCount; i++) {
            for (Column column : columns) {
                stringBuilder.append(column.getValues().get(i)).append("|"); //use delimiter to avoid ambiguities
            }

            stringBuilder.setLength(stringBuilder.length() - 1); //remove last delimiter
            String rowAsString = stringBuilder.toString();
            rowsAsString.add(rowAsString);
            stringBuilder.setLength(0);
        }

        return rowsAsString;
    }

    private Map<String, Double> getProbabilityMap(List<String> rowsAsString, int rowCount) {

        Map<String, Double> probabilityMap = new HashMap<>();

        for (String rowAsString : rowsAsString) {
            probabilityMap.merge(rowAsString, 1.0, Double::sum);
        }

        probabilityMap.replaceAll((k, v) -> v / rowCount);
        return probabilityMap;
    }

    private Map<String, Map<String, Double>> getProbabilityMapGivenX(List<String> rowsAsStringX, List<String> rowsAsStringA, int rowCount) {

        Map<String, Map<String, Double>> probabilityMapGivenX = new HashMap<>();

        for (int i = 0; i < rowCount; i++) {
            String x = rowsAsStringX.get(i);
            String a = rowsAsStringA.get(i);

            Map<String, Double> innerMap = probabilityMapGivenX.computeIfAbsent(x, k -> new HashMap<>());
            innerMap.merge(a, 1.0, Double::sum);
        }

        for (Map<String, Double> map : probabilityMapGivenX.values()) {
            double total = map.values().stream().mapToDouble(Double::doubleValue).sum();
            map.replaceAll((k, v) -> v / total);
        }

        return probabilityMapGivenX;
    }

    /**
     * Which excess measure {@link #computeGPDEPScore()} returns. Read once, so
     * a run cannot change measure halfway through and leave the cache holding a
     * mixture. See SF_FD_MEASURE.md.
     */
    private static final boolean MU_PLUS_MEASURE =
            "MU_PLUS".equalsIgnoreCase(System.getProperty("smx.sf.fdMeasure", "GPDEP"));

    public double calculateGPDEPScore() {
        if (this.gpdepScoreCache == null) {
            this.gpdepScoreCache = computeGPDEPScore();
        }
        return this.gpdepScoreCache;
    }

    private double computeGPDEPScore() {

        int rowCount = this.dependant.getValues().size();

        List<String> rowsForXAsStrings = getRowsAsStrings(this.determinant, rowCount);
        List<String> rowsForAAsStrings = this.getDependant().getValues();

        Map<String, Double> probabilitiesForX = getProbabilityMap(rowsForXAsStrings, rowCount);
        Map<String, Double> probabilitiesForA = getProbabilityMap(rowsForAAsStrings, rowCount);
        Map<String, Map<String, Double>> probabilitiesForAGivenX = getProbabilityMapGivenX(rowsForXAsStrings, rowsForAAsStrings, rowCount);

        double pdepXA = 0.0;

        for(String x : probabilitiesForX.keySet()) {

            double probabilityForX = probabilitiesForX.get(x);
            double pdepAGivenX = 0;
            Map<String, Double> probabilitiesForAGivenXMap = probabilitiesForAGivenX.get(x);

            // Sum P(a|x)^2 over the values of A that actually occur with x.
            // Iterating every distinct value of A instead and defaulting the
            // rest to zero adds nothing to the sum but makes this loop
            // O(|distinct X| * |distinct A|); on high-cardinality columns that
            // is quadratic in the number of rows. Iterating the co-occurring
            // values only is mathematically identical and linear overall.
            if (probabilitiesForAGivenXMap != null) {
                for (double probabilityForAGivenX : probabilitiesForAGivenXMap.values()) {
                    pdepAGivenX = pdepAGivenX + probabilityForAGivenX * probabilityForAGivenX;
                }
            }

            pdepXA = pdepXA + probabilityForX * pdepAGivenX;
        }

        double selfDependencyScoreA = probabilitiesForA.values().stream()
                .mapToDouble(p -> p * p)
                .sum();

        double epdepXA = selfDependencyScoreA + ((probabilitiesForX.keySet().size() - 1.0) / (rowCount - 1.0)) * (1 - selfDependencyScoreA);

        // gpdep is the raw excess of pdep over its expectation under
        // independence. mu+ divides that excess by the headroom the expectation
        // leaves, (1 - epdep), which is the correction Parciak et al. (VLDB J
        // 2025) recommend after comparing AFD measures by ranking quality:
        // "our recommendation for linear AFD discovery is therefore the
        // little-known measure mu+".
        //
        // Selected by -Dsmx.sf.fdMeasure=MU_PLUS. Absent the property the value
        // is the unchanged gpdep, so every prior run is reproduced exactly.
        // Matches this project's own Python definition in
        // afd_measure_mechanism.py: mu_plus = max((pdep - epdep)/(1 - epdep), 0).
        if (MU_PLUS_MEASURE) {
            double headroom = 1.0 - epdepXA;
            // epdep at or above 1 means independence already explains
            // everything; there is no excess to normalise and the dependency
            // carries no information.
            return headroom <= 0.0 ? 0.0 : (pdepXA - epdepXA) / headroom;
        }

        return pdepXA - epdepXA;
    }

    public double calculateNGPDEPScore() {
        if (this.getDeterminant().isEmpty()) {
            return 0.0;
        }

        Column A = this.getDependant();
        double gpdepXA = this.calculateGPDEPScore();

        Collection<FunctionalDependency> fdsWithDependentA =
                A.getTable().getDatabase().getMetadata().getFunctionalDependenciesWithGivenDependent(A);

        double sum = fdsWithDependentA.stream()
                .mapToDouble(FunctionalDependency::calculateGPDEPScore)
                .sum();

        return sum == 0.0 ? 0.0 : Math.max(gpdepXA / sum, 0.0);
    }

}
