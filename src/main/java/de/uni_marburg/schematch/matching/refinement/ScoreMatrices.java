package de.uni_marburg.schematch.matching.refinement;

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
}
