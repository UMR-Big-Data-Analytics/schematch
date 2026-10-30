package de.uni_marburg.schematch.matching.refinement;

import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.matching.similarityFlooding.ColumnClasses;
import de.uni_marburg.schematch.utils.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** ClassTermMatcher reads the classes SF's native class nodes use, value shapes or rule-based semantic types. */
class ClassTermClassesTest {
    @TempDir Path dir;

    @Test
    void semanticTypeClassesAreTheGraphsClasses() throws Exception {
        Files.createDirectories(dir.resolve("source"));
        Files.createDirectories(dir.resolve("target"));
        Files.writeString(dir.resolve("source/s.csv"), "mail,year\na@b.de,1999\nc@d.org,2001\n");
        Files.writeString(dir.resolve("target/t.csv"), "when,contact\n1987,x@y.com\n2020,z@w.net\n");
        Configuration config = Configuration.getInstance();
        boolean old = config.isReadDependencies();
        Scenario scenario;
        try { config.setReadDependencies(false); scenario = new Scenario(dir.toString()); }
        finally { config.setReadDependencies(old); }
        assertArrayEquals(new String[]{"email_", "year_"}, ColumnClasses.classes(scenario.getSourceDatabase(), ColumnClasses.Kind.SEMTYPE));
        ClassTermMatcher matcher = new ClassTermMatcher();
        matcher.setDatatypeWeight(0);
        matcher.setClasses("SEMTYPE");
        matcher.setWeight(0.1);
        double[][] out = matcher.combine(new float[2][2], scenario);
        assertArrayEquals(new double[]{0, 0.1}, out[0], 1e-12);
        assertArrayEquals(new double[]{0.1, 0}, out[1], 1e-12);
        matcher.setClasses("SHAPE");
        assertArrayEquals(ClassTermMatcher.classes(scenario.getSourceDatabase()),
                ColumnClasses.classes(scenario.getSourceDatabase(), ColumnClasses.Kind.SHAPE));
        matcher.setClasses("COLOUR");
        assertThrows(IllegalArgumentException.class, () -> matcher.combine(new float[2][2], scenario));
    }
}
