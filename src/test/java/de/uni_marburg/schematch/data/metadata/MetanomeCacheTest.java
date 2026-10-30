package de.uni_marburg.schematch.data.metadata;

import de.uni_marburg.schematch.TestUtils;
import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.data.metadata.dependency.Metanome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetanomeCacheTest {

    @Test
    void executeFDReadsMetadataFile() {
        Scenario scenario = TestUtils.getTestData().getScenarios().get("test1");
        Table table = scenario.getSourceDatabase().getTables().get(0);

        Set<String> fds = Metanome.executeFD(List.of(table)).stream()
                .map(Object::toString)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "[authors.aid] --> authors.name",
                "[authors.name] --> authors.aid"
        ), fds);
    }

    @Test
    void executeUCCReadsMetadataFile() {
        Scenario scenario = TestUtils.getTestData().getScenarios().get("test1");
        Table table = scenario.getSourceDatabase().getTables().get(0);

        Set<String> uccs = Metanome.executeUCC(List.of(table)).stream()
                .map(Object::toString)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "[authors.aid]",
                "[authors.name]"
        ), uccs);
    }
}
