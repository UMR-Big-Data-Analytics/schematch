package de.uni_marburg.schematch.matching.similarityFlooding;

import de.uni_marburg.schematch.data.Column;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ColumnRelationFloodingTest {
    private static Column column(String name, String... values) {
        return new Column(name, List.of(values));
    }

    private static ColumnRelationFlooding.Channel channel(String name, double[][] matrix) {
        return new ColumnRelationFlooding.Channel(name, matrix);
    }

    private static void same(double[][] expected, double[][] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) assertArrayEquals(expected[i], actual[i], 1e-10);
    }

    @Test void withinIndUsesDependentRemovedBackground() {
        List<Column> cols = List.of(column("a", "a", "b", "c"),
                column("b", "a", "b", "x"), column("c", "d", "e", "f"));
        double[][] gcov = ColumnRelationFlooding.withinIndGcov(cols, null);
        assertEquals(1.0 / 3.0, gcov[0][1], 1e-12);
        assertEquals(0, gcov[0][0]);
        assertEquals(0, gcov[0][2]);
    }

    @Test void nullValidityRestoresEncryptedIndInvariance() {
        List<Column> plain = List.of(column("a", "", "a", "b"),
                column("b", "", "a", "x"), column("c", "z", "z", "z"));
        List<Column> cipher = List.of(column("hashA", "hashed-null", "h1", "h2"),
                column("hashB", "hashed-null", "h1", "h3"), column("hashC", "h4", "h4", "h4"));
        boolean[][] mask = {{false, true, true}, {false, true, true}, {true, true, true}};
        same(ColumnRelationFlooding.withinIndGcov(plain, null),
                ColumnRelationFlooding.withinIndGcov(cipher, mask));
        assertThrows(IllegalArgumentException.class,
                () -> ColumnRelationFlooding.withinIndGcov(cipher, new boolean[][]{{true}}));
    }

    @Test void gpdepIsRenameInvariantAndDoesNotRewardKeysOrConstants() {
        List<Column> plain = List.of(column("a", "a", "a", "b", "b"),
                column("b", "x", "x", "y", "y"), column("key", "1", "2", "3", "4"),
                column("constant", "c", "c", "c", "c"));
        List<Column> cipher = List.of(column("A", "c91", "c91", "c37", "c37"),
                column("B", "c56", "c56", "c24", "c24"), column("K", "u3", "u2", "u1", "u4"),
                column("C", "foo", "foo", "foo", "foo"));
        double[][] gpdep = ColumnRelationFlooding.positiveGpdep(plain);
        same(gpdep, ColumnRelationFlooding.positiveGpdep(cipher));
        assertTrue(gpdep[0][1] > 0);
        assertEquals(0, gpdep[2][1]);
        assertEquals(0, gpdep[0][3]);
        assertEquals(0, gpdep[0][0]);
    }

    @Test void uncertaintyAndJaccardAreRenameInvariantWithOwnerNullMask() {
        List<Column> plain = List.of(column("a", "", "a", "a", "b"),
                column("b", "", "x", "x", "y"), column("constant", "", "z", "z", "z"));
        List<Column> cipher = List.of(column("A", "null-a", "h1", "h1", "h2"),
                column("B", "null-b", "h3", "h3", "h4"), column("C", "null-c", "h5", "h5", "h5"));
        boolean[][] mask = {{false, true, true, true}, {false, true, true, true}, {false, true, true, true}};
        same(ColumnRelationFlooding.uncertainty(plain, null),
                ColumnRelationFlooding.uncertainty(cipher, mask));
        same(ColumnRelationFlooding.withinJaccard(plain, null),
                ColumnRelationFlooding.withinJaccard(cipher, mask));
        assertEquals(1.0, ColumnRelationFlooding.uncertainty(plain, null)[0][1], 1e-12);
        assertEquals(0.0, ColumnRelationFlooding.uncertainty(plain, null)[0][2], 1e-12);
        assertEquals(0.0, ColumnRelationFlooding.withinJaccard(plain, null)[0][1], 1e-12);
    }

    @Test void typedParallelMessagesSurviveAndAreChannelBalanced() {
        double[][] fd = {{0, 1}, {0, 0}}, ind = {{0, 0.4}, {0, 0}};
        var channels = List.of(channel("fd", fd), channel("ind", ind));
        var graph = ColumnRelationFlooding.connectivity(2, 2, channels, channels,
                PropagationCoefficientPolicy.INVERSE_PRODUCT);
        assertEquals(4, graph.sources().length);
        assertEquals(2, graph.rowPtr()[1] - graph.rowPtr()[0]);
        double total = Arrays.stream(graph.coefficients(), graph.rowPtr()[0], graph.rowPtr()[1]).sum();
        assertEquals(0.7, total, 1e-12);
    }

    @Test void directionIsPreservedAndMissingEdgesGiveNoAgreement() {
        var a = List.of(channel("fd", new double[][]{{0, 1}, {0, 0}}));
        var b = List.of(channel("fd", new double[][]{{0, 0}, {1, 0}}));
        var graph = ColumnRelationFlooding.connectivity(2, 2, a, b,
                PropagationCoefficientPolicy.INVERSE_PRODUCT);
        assertEquals(2, graph.sources().length);
        assertEquals(graph.rowPtr()[0], graph.rowPtr()[1]); // (0,0) has no support
        assertEquals(2, graph.sources()[graph.rowPtr()[1]]); // (1,0) -> (0,1)
    }

    @Test void noStructuralInformationAndMismatchedTypesPreserveSeedExactly() {
        double[][] seed = {{0.2, 0.7}, {0.8, 0.3}};
        var a = List.of(channel("fd", new double[][]{{0, 1}, {0, 0}}));
        var b = List.of(channel("ind", new double[][]{{0, 1}, {0, 0}}));
        same(seed, ColumnRelationFlooding.propagate(seed, a, b,
                PropagationCoefficientPolicy.INVERSE_PRODUCT, FixpointFormula.FORMULA_B));
        same(seed, ColumnRelationFlooding.propagate(seed, List.of(), List.of(),
                PropagationCoefficientPolicy.INVERSE_PRODUCT, FixpointFormula.FORMULA_B));
    }

    private static double[][] permute(double[][] matrix, int[] rows, int[] cols) {
        double[][] out = new double[rows.length][cols.length];
        for (int i = 0; i < rows.length; i++) for (int j = 0; j < cols.length; j++) out[i][j] = matrix[rows[i]][cols[j]];
        return out;
    }

    @Test void independentColumnPermutationsCommuteWithPropagation() {
        double[][] seed = {{0.2, 0.7, 0.1}, {0.8, 0.3, 0.2}, {0.1, 0.4, 0.9}};
        double[][] a = {{0, 0.9, 0.1}, {0.4, 0, 0.2}, {0.7, 0, 0}};
        double[][] b = {{0, 0.3, 0.8}, {0, 0, 0.6}, {0.3, 0.7, 0}};
        int[] p = {2, 0, 1}, q = {1, 2, 0};
        double[][] original = ColumnRelationFlooding.propagate(seed, List.of(channel("fd", a)), List.of(channel("fd", b)),
                PropagationCoefficientPolicy.INVERSE_PRODUCT, FixpointFormula.FORMULA_B);
        double[][] reordered = ColumnRelationFlooding.propagate(permute(seed, p, q),
                List.of(channel("fd", permute(a, p, p))), List.of(channel("fd", permute(b, q, q))),
                PropagationCoefficientPolicy.INVERSE_PRODUCT, FixpointFormula.FORMULA_B);
        same(permute(original, p, q), reordered);
    }

    @Test void emptyAndZeroSeedsStayFinite() {
        var channels = List.of(channel("fd", new double[][]{{0, 1}, {0, 0}}));
        same(new double[2][2], ColumnRelationFlooding.propagate(new double[2][2], channels, channels,
                PropagationCoefficientPolicy.INVERSE_PRODUCT, FixpointFormula.FORMULA_B));
        same(new double[0][0], ColumnRelationFlooding.propagate(new double[0][0], List.of(), List.of(),
                PropagationCoefficientPolicy.INVERSE_PRODUCT, FixpointFormula.FORMULA_B));
    }

    @Test void duplicateChannelsAndInvalidWeightsFailLoudly() {
        var c = channel("fd", new double[][]{{0, 1}, {0, 0}});
        assertThrows(IllegalArgumentException.class, () -> ColumnRelationFlooding.connectivity(2, 2,
                List.of(c, c), List.of(c), PropagationCoefficientPolicy.INVERSE_PRODUCT));
        assertThrows(IllegalArgumentException.class, () -> ColumnRelationFlooding.connectivity(2, 2,
                List.of(channel("fd", new double[][]{{0, Double.NaN}, {0, 0}})), List.of(c),
                PropagationCoefficientPolicy.INVERSE_PRODUCT));
    }

    @Test void supportedIdentityMatchesDoNotTurnIntoCrossMatches() {
        var relations = List.of(channel("fd", new double[][]{{0, 0.6}, {0, 0}}));
        double[][] result = ColumnRelationFlooding.propagate(new double[][]{{0.8, 0}, {0, 0.9}},
                relations, relations, PropagationCoefficientPolicy.INVERSE_PRODUCT, FixpointFormula.FORMULA_B);
        assertTrue(result[0][0] > result[0][1]);
        assertTrue(result[1][1] > result[1][0]);
        assertEquals(0, result[0][1]);
        assertEquals(0, result[1][0]);
    }
}
