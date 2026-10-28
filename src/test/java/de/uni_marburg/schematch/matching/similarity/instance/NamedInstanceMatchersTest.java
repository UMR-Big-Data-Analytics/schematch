package de.uni_marburg.schematch.matching.similarity.instance;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import de.uni_marburg.schematch.utils.Configuration;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NamedInstanceMatchersTest {
    static Table table(String name, String[] labels, String[]... values) {
        List<Column> cols = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) cols.add(new Column(labels[i], List.of(values[i])));
        return new Table(name, List.of(labels), cols, name + ".csv", null);
    }

    @Test
    void pmfHasAnExplicitMetricAndRetainsHistoricalMultiplicityAndNullSemantics() {
        TablePair pair = new TablePair(table("s", new String[]{"a"}, new String[]{"a","a","b","b"}),
                table("t", new String[]{"x"}, new String[]{"x","x","x","x"}));
        PMFMatcher matcher = new PMFMatcher();
        assertEquals(.5, matcher.match(pair)[0][0], 1e-7);
        matcher.configure(new Configuration.MatcherConfiguration("PMFMatcher", "similarity.instance", Map.of("distance", "HELLINGER")));
        assertEquals(1 - Math.sqrt(1 - 1/Math.sqrt(2)), matcher.match(pair)[0][0], 1e-7);
        assertThrows(IllegalArgumentException.class, () -> matcher.setDistance("JSD"));
        assertEquals(.5, DistributionMatcher.distributionSimilarity(List.of("","","b","b"), List.of("x","x","x","x"), "L2"), 1e-7);
        String old = System.getProperty("smx.pmf.distance");
        try {
            System.setProperty("smx.pmf.distance", "hellinger");
            assertEquals(.5, new PMFMatcher().match(pair)[0][0], 1e-7);
        } finally {
            if (old == null) System.clearProperty("smx.pmf.distance"); else System.setProperty("smx.pmf.distance", old);
        }
    }

    @Test
    void valueShapeRolesDescribeOtherColumnsAndExcludeSelfSimilarity() {
        Table t = table("s", new String[]{"a", "a2", "b"},
                new String[]{"a","a"}, new String[]{"a","a"}, new String[]{"b","b"});
        double[][] features = ValueShapeRoleMatcher.features(t);
        assertArrayEquals(new double[]{1,.5,.5}, features[0], 1e-7);
        assertArrayEquals(new double[]{0,0,0}, features[2], 1e-7);
        assertEquals(1-2.0/3, new ValueShapeRoleMatcher().match(new TablePair(t,t))[0][2], 1e-7);
    }

    @Test
    void nullRolesUsePartialColumnsAndConnectedComponents() {
        Table t = table("s", new String[]{"key","name","zip","empty"},
                new String[]{"1","2","3","4","5","6"},
                new String[]{"Ada","","Lin","","null",""},
                new String[]{"101","102","","","103",""},
                new String[]{"","","","","",""});
        NullStructureMatcher matcher = new NullStructureMatcher();
        double[][] features = matcher.features(t);
        assertArrayEquals(new double[]{0,1,0,0,0,0}, features[0], 1e-7);
        assertArrayEquals(new double[]{2.0/3,0,0,0,.4,.25}, features[1], 1e-7);
        assertArrayEquals(new double[]{1,0,1,0,0,0}, features[3], 1e-7);
        matcher.setThreshold(.4);
        features = matcher.features(t);
        assertEquals(1, features[1][3]);
        assertEquals(.5, features[1][5]);
        matcher.setMaxRows(1);
        assertEquals(0, matcher.features(t)[1][0]);
    }
}
