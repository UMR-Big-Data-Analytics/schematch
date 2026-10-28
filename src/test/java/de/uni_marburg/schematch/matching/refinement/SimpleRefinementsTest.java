package de.uni_marburg.schematch.matching.refinement;

import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.matching.control.ConstantMatcher;
import de.uni_marburg.schematch.matching.similarity.label.TableQualifiedNameMatcher;
import de.uni_marburg.schematch.matchtask.MatchTask;
import de.uni_marburg.schematch.matchtask.matchstep.MatchingStep;
import de.uni_marburg.schematch.matchtask.matchstep.SimMatrixBoostingStep;
import de.uni_marburg.schematch.boosting.IdentitySimMatrixBoosting;
import de.uni_marburg.schematch.utils.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SimpleRefinementsTest {
    @TempDir Path dir;

    private static void matrix(double[][] expected, float[][] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) for (int j = 0; j < expected[i].length; j++)
            assertEquals(expected[i][j], actual[i][j], 1e-6, "cell " + i + "," + j);
    }

    @Test
    void selectionMatchesIndependentPythonReferencesOnRectangularScores() {
        // sf-matcher-study/layer3b/x103_selection.py, evaluated on float32 inputs, 2026-10-14.
        float[][] base = {{.9f,.6f,.1f},{.8f,.85f,.2f}};
        matrix(new double[][]{{.9143775337749709,.11850085529934659,.46712155862593335},
                {.0856224662250291,.8814991447006534,.5328784413740667}}, new SinkhornMatcher().refine(base, null));
        matrix(new double[][]{{2.000001,.6250000477302835,0},{.875000040745364,1.937501057625586,.12500000582076629}},
                new HungarianBonusMatcher().refine(base, null));
        assertEquals(.9f, base[0][0]); // Neither transformation mutates its input.
        assertEquals(0, new HungarianBonusMatcher().refine(new float[0][0], null).length);
        matrix(new double[][]{{.5,.5},{.5,.5}}, new SinkhornMatcher().refine(new float[][]{{7,7},{7,7}}, null));
        assertThrows(IllegalArgumentException.class, () -> new SinkhornMatcher().refine(new float[][]{{Float.NaN}}, null));
    }

    @Test
    void qualifiedNamesMatchX105TokensAndRespectScenarioTableOffsets() throws Exception {
        // x105.q_term: [Users.customerID, Orders.orderId] x [User.customer_id, Orders.customerID, Products.productId].
        assertEquals(1, TableQualifiedNameMatcher.similarity("Users", "customerID", "User", "customer_id"));
        assertEquals(.5, TableQualifiedNameMatcher.similarity("Users", "customerID", "Orders", "customerID"), 1e-7);
        assertEquals(1.0/3, TableQualifiedNameMatcher.similarity("Orders", "orderId", "Products", "productId"), 1e-7);
        assertEquals(0, TableQualifiedNameMatcher.similarity("", "", "", ""));
        Scenario scenario = scenario();
        QualifiedNameRefinementMatcher matcher = new QualifiedNameRefinementMatcher();
        float[][] out = matcher.refine(new float[2][3], scenario);
        // Files are sorted Orders, Users and Orders, Products, User by the Java reader.
        matrix(new double[][]{{1.0/3,1.0/6,.125},{.25,.125,.5}}, out);
    }

    private Scenario scenario() throws Exception {
        Files.createDirectories(dir.resolve("source"));
        Files.createDirectories(dir.resolve("target"));
        Files.writeString(dir.resolve("source/Users.csv"), "customerID\n1\n");
        Files.writeString(dir.resolve("source/Orders.csv"), "orderId\n2\n");
        Files.writeString(dir.resolve("target/User.csv"), "customer_id\n1\n");
        Files.writeString(dir.resolve("target/Orders.csv"), "customerID\n1\n");
        Files.writeString(dir.resolve("target/Products.csv"), "productId\n3\n");
        Configuration config = Configuration.getInstance();
        boolean old = config.isReadDependencies();
        try { config.setReadDependencies(false); return new Scenario(dir.toString()); }
        finally { config.setReadDependencies(old); }
    }

    @Test
    void pipelineUsesExplicitRawOrPreviousInput() throws Exception {
        Scenario scenario = scenario();
        ConstantMatcher base = new ConstantMatcher();
        MatchingStep first = new MatchingStep(false, false, 1, List.of(base));
        SimMatrixBoostingStep boosted = new SimMatrixBoostingStep(false, false, 1, new IdentitySimMatrixBoosting());
        HungarianBonusMatcher bonus = new HungarianBonusMatcher();
        MatchingStep second = new MatchingStep(false, false, 2, List.of(bonus));
        MatchTask task = new MatchTask(null, scenario, List.of(first, boosted, second), List.of());
        task.getSimMatrices().put(first, new HashMap<>());
        task.getSimMatrices().put(boosted, new HashMap<>());
        float[][] raw = {{9,1,0},{0,9,1}}, selected = {{0,0,9},{9,0,0}};
        task.setSimMatrix(first, base, raw);
        task.setSimMatrix(boosted, base, selected);
        assertTrue(bonus.match(task, second)[0][0] > 1);
        bonus.setInitialMatrix("PREVIOUS");
        assertTrue(bonus.match(task, second)[0][2] > 1);
        bonus.setBaseMatcherIndex(2);
        assertThrows(IllegalArgumentException.class, () -> bonus.match(task, second));
    }
}
