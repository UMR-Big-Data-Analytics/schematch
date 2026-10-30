package de.uni_marburg.schematch.matching.refinement;

import de.uni_marburg.schematch.data.Scenario;

final class ScoreMatrices {
    private ScoreMatrices() {}

    static void validate(float[][] matrix) {
        if (matrix == null) throw new IllegalArgumentException("Missing base matrix");
        int columns = matrix.length == 0 ? 0 : matrix[0].length;
        for (float[] row : matrix) {
            if (row.length != columns) throw new IllegalArgumentException("Ragged score matrix");
            for (float value : row) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite base score");
        }
    }

    /** validate, plus the scenario's source x target shape. */
    static void validate(float[][] matrix, Scenario scenario) {
        validate(matrix);
        if (matrix.length != scenario.getSourceDatabase().getNumColumns()) throw new IllegalArgumentException("Source dimensions differ");
        for (float[] row : matrix) if (row.length != scenario.getTargetDatabase().getNumColumns())
            throw new IllegalArgumentException("Target dimensions differ");
    }
}
