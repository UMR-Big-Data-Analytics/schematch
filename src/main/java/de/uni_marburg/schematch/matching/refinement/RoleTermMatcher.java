package de.uni_marburg.schematch.matching.refinement;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Database;
import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.data.metadata.DatabaseMetadata;
import de.uni_marburg.schematch.data.metadata.dependency.FunctionalDependency;
import de.uni_marburg.schematch.data.metadata.dependency.InclusionDependency;
import de.uni_marburg.schematch.data.metadata.dependency.UniqueColumnCombination;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Role term q+ + beta D + weight * R (rung (c)). R_ij = 1 - mean_k |r_k(i) - r_k(j)| over six role features
 * of each column, each divided by its maximum over the columns of its side (0 if that is 0): FD left-hand-side
 * count, FD right-hand-side count, number of UCCs containing it, key flag (in a UCC of the smallest arity on its
 * side), unary IND out-degree and in-degree. Reference: sf-matcher-study/explore_deproles.py roles (corrected FD
 * parse) and reference/score_roles_reg.py role_matrix.
 *
 * <p>weight 0.2 is the frozen gamma for readable data; 1.0 was frozen for recoded (hashed) values
 * (sf-matcher-study/reference/PREREGISTRATION_LAYERS.md, role amendment; X46's choice on Valentine).
 *
 * <p>Reads the profiles Schematch loaded (readDependencies). The Python reference counts FD lines as written;
 * Schematch expands an FD with an empty left-hand side into one FD per other column and drops duplicate lines,
 * so profiles with either differ from the reference (Valentine's have neither).
 */
@Getter
@Setter
@EqualsAndHashCode(callSuper = true)
public final class RoleTermMatcher extends AdditiveTermMatcher {
    static final int FEATURES = 6;
    private double weight = 0.2;

    @Override
    double[][] term(float[][] scores, Scenario scenario) {
        requireFinite(weight);
        double[][] source = roles(scenario.getSourceDatabase()), target = roles(scenario.getTargetDatabase());
        double[][] out = new double[source.length][target.length];
        for (int i = 0; i < source.length; i++) for (int j = 0; j < target.length; j++) {
            double distance = 0;
            for (int k = 0; k < FEATURES; k++) distance += Math.abs(source[i][k] - target[j][k]);
            out[i][j] = weight * (1.0 - distance / FEATURES);
        }
        return out;
    }

    /** Normalised role vectors, in matrix order. */
    static double[][] roles(Database database) {
        DatabaseMetadata metadata = database.getMetadata();
        if (metadata == null) throw new IllegalStateException("RoleTermMatcher needs dependency profiles; enable readDependencies");
        List<Column> columns = database.getColumns();
        Map<Column, Integer> index = new IdentityHashMap<>();
        for (int c = 0; c < columns.size(); c++) index.put(columns.get(c), c);
        double[][] f = new double[columns.size()][FEATURES];
        for (FunctionalDependency fd : metadata.getFds()) {
            for (Column left : fd.getDeterminant()) count(f, index, left, 0);
            count(f, index, fd.getDependant(), 1);
        }
        int minArity = Integer.MAX_VALUE;
        for (UniqueColumnCombination ucc : metadata.getUccs()) minArity = Math.min(minArity, ucc.getColumnCombination().size());
        for (UniqueColumnCombination ucc : metadata.getUccs()) {
            for (Column column : ucc.getColumnCombination()) {
                count(f, index, column, 2);
                Integer c = index.get(column);
                if (c != null && ucc.getColumnCombination().size() == minArity) f[c][3] = 1;
            }
        }
        // InputReader stores a profile line "A [= B" with A as referenced and B as dependant; out-degree counts A,
        // as the reference does. (Out- and in-degree enter R symmetrically, so R does not depend on this.)
        for (InclusionDependency ind : metadata.getInds()) {
            if (ind.getDependant().size() != 1 || ind.getReferenced().size() != 1) continue;
            count(f, index, ind.getReferenced().iterator().next(), 4);
            count(f, index, ind.getDependant().iterator().next(), 5);
        }
        for (int k = 0; k < FEATURES; k++) {
            double max = 0;
            for (double[] row : f) max = Math.max(max, row[k]);
            for (double[] row : f) row[k] = max > 0 ? row[k] / max : 0.0;
        }
        return f;
    }

    private static void count(double[][] f, Map<Column, Integer> index, Column column, int feature) {
        Integer c = index.get(column);
        if (c != null) f[c][feature]++;
    }
}
