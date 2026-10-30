package de.uni_marburg.schematch.constraintScore;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.data.metadata.dependency.FunctionalDependency;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class ProbabilisticDependencyTest {

    public static Column X = new Column("X", List.of("1", "1", "2", "2", "3"));
    public static Column A = new Column("Y", List.of("1", "1", "1", "2", "2"));
    Table table = new Table("Test", List.of("X", "A"), List.of(X, A), null, null);

    public static Column postCode = new Column("post Code", List.of("35037", "35037", "42331", "56894", "37291", "37291"));
    // exact FD postCode -> city
    public static Column city = new Column("city", List.of("Marburg", "Marburg", "Düsseldorf", "Frankfurt", "Köln", "Köln"));
    // two violating rows
    public static Column city2 = new Column("city2", List.of("Marburg", "Frankfurt", "Düsseldorf", "Frankfurt", "Köln", "Frankfurt"));
    // city2 with Marburg and Frankfurt swapped
    public static Column city3 = new Column("city3", List.of("Frankfurt", "Marburg", "Düsseldorf", "Marburg", "Köln", "Marburg"));
    public static Column random = new Column("random", List.of("1", "2", "3", "3", "4", "d"));
    public static Column constant = new Column("constant", List.of("F", "F", "F", "F", "F", "F"));

    Table table2 = new Table("Test2", List.of("post Code", "city", "city2", "city3", "random", "constant"),
            List.of(postCode, city, city2, city3, random, constant), null, null);

    @Test
    void gpdepTest() {
        FunctionalDependency X_A = new FunctionalDependency(List.of(X), A);
        FunctionalDependency A_X = new FunctionalDependency(List.of(A), X);

        Assertions.assertEquals(0.04, X_A.calculateGPDEPScore(), 0.001);
        Assertions.assertEquals(0.013, A_X.calculateGPDEPScore(), 0.001);
    }

    @Test
    void gpdepOrderingTest() {
        double exact = new FunctionalDependency(List.of(postCode), city).calculateGPDEPScore();
        double violated = new FunctionalDependency(List.of(postCode), city2).calculateGPDEPScore();
        double relabelled = new FunctionalDependency(List.of(postCode), city3).calculateGPDEPScore();
        double noise = new FunctionalDependency(List.of(postCode), random).calculateGPDEPScore();
        double onConstant = new FunctionalDependency(List.of(postCode), constant).calculateGPDEPScore();

        Assertions.assertEquals(0.289, exact, 0.001);
        Assertions.assertTrue(exact > violated);
        // gpdep depends on value frequencies only, not on the values themselves
        Assertions.assertEquals(violated, relabelled, 1e-12);
        // with four determinant groups on six rows, two violations already fall below the
        // independence expectation, as does noise; gpdep does not order these two
        Assertions.assertEquals(-0.067, violated, 0.001);
        Assertions.assertEquals(-0.022, noise, 0.001);
        // a constant dependent is determined by anything, so there is no excess over independence
        Assertions.assertEquals(0.0, onConstant, 1e-12);
    }
}
