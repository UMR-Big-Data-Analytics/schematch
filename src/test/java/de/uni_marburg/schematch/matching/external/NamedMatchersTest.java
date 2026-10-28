package de.uni_marburg.schematch.matching.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matching.MatcherFactory;
import de.uni_marburg.schematch.matching.MatrixRefinementMatcher;
import de.uni_marburg.schematch.matching.refinement.SinkhornMatcher;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import de.uni_marburg.schematch.utils.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class NamedMatchersTest {
    @TempDir Path dir;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void factoryConfiguresInheritedBridgeFieldsAndPreservesAlgorithmIdentity() throws Exception {
        ComaMatcher coma = (ComaMatcher) new MatcherFactory().createMatcherInstance(
                new Configuration.MatcherConfiguration("ComaMatcher", "external", Map.of(
                        "serverPort", 5123, "timeoutSeconds", 9, "options", "{\"max_rows\":10}", "failOnError", false)));
        assertEquals("coma", coma.getMethod());
        assertEquals(5123, coma.getServerPort());
        assertEquals(9, coma.getTimeoutSeconds());
        assertFalse(coma.getFailOnError());
        assertTrue(coma.toString().startsWith("ComaMatcher("));
        assertTrue(coma.toString().contains("max_rows"));
        assertThrows(IllegalArgumentException.class, () -> coma.setMethod("cupid"));
        assertThrows(RuntimeException.class, () -> coma.configure(new Configuration.MatcherConfiguration(
                "ComaMatcher", "external", Map.of("unknownParameter", true))));

        SinkhornMatcher sinkhorn = new SinkhornMatcher();
        sinkhorn.configure(new Configuration.MatcherConfiguration("SinkhornMatcher", "refinement", Map.of(
                "tau", .025, "iterations", 30, "base", "{\"name\":\"PMFMatcher\",\"package\":\"similarity.instance\"}")));
        assertEquals(.025, sinkhorn.getTau(), 0);
        assertEquals(30, sinkhorn.getIterations());
        assertFalse(sinkhorn.getBase().isEmpty());
    }

    private Table table(String name, List<String> labels) {
        return new Table(name, labels, labels.stream().map(l -> new Column(l, List.of("x"))).toList(),
                dir.resolve(name + ".csv").toString(), null);
    }

    @Test
    void namedTableAndScenarioAdaptersUseTheirActualScopeAndReportHttpFailures() throws Exception {
        AtomicReference<JsonNode> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/match", exchange -> {
            JsonNode body = json.readTree(exchange.getRequestBody());
            request.set(body);
            int n = body.get("source_labels").size(), m = body.get("target_labels").size();
            StringBuilder response = new StringBuilder();
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < m; j++) response.append(j == 0 ? "" : " ").append("0.25");
                response.append('\n');
            }
            boolean fail = body.path("options").path("fail").asBoolean(false);
            byte[] bytes = (fail ? "backend unavailable" : response.toString()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(fail ? 503 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            TablePair pair = new TablePair(table("s", List.of("a", "b")), table("t", List.of("c", "d")));
            CupidMatcher cupid = new CupidMatcher();
            cupid.setServerPort(server.getAddress().getPort());
            assertEquals(.25f, cupid.match(pair)[1][1]);
            assertEquals("cupid", request.get().get("method").asText());
            assertTrue(request.get().has("source_path"));
            assertFalse(request.get().has("scenario_path"));
            cupid.setOptions("{\"fail\":true}");
            assertThrows(RuntimeException.class, () -> cupid.match(pair));

            Configuration config = Configuration.getInstance();
            boolean oldDependencies = config.isReadDependencies();
            config.setReadDependencies(false);
            Scenario scenario;
            try {
                Files.createDirectories(dir.resolve("source"));
                Files.createDirectories(dir.resolve("target"));
                Files.writeString(dir.resolve("source/a.csv"), "id,name\n1,Ada\n");
                Files.writeString(dir.resolve("source/b.csv"), "code\nx\n");
                Files.writeString(dir.resolve("target/c.csv"), "id,name\n1,Ada\n");
                scenario = new Scenario(dir.toString());
            } finally { config.setReadDependencies(oldDependencies); }
            DependencyMatcher dep = new DependencyMatcher();
            dep.setServerPort(server.getAddress().getPort());
            float[][] result = dep.matchScenario(scenario, new float[3][2]);
            assertEquals(3, result.length);
            assertEquals(2, result[0].length);
            assertEquals("dep", request.get().get("method").asText());
            assertEquals(dir.toString(), request.get().get("scenario_path").asText());
            assertEquals("b.code", request.get().get("source_labels").get(2).asText());
            assertEquals(3, request.get().get("base").size());
            assertFalse(request.get().has("source_path"));
        } finally { server.stop(0); }
    }

    @Test
    void exactAndReplayAreExplicitDifferentPresets() throws Exception {
        ExactEntityAlignmentMatcher exact = new ExactEntityAlignmentMatcher();
        StudyReplayEntityAlignmentMatcher replay = new StudyReplayEntityAlignmentMatcher();
        assertFalse(json.readTree(exact.getOptions()).get("greedy").asBoolean());
        JsonNode options = json.readTree(replay.getOptions());
        assertFalse(options.get("greedy").asBoolean());
        assertFalse(options.get("canonical").asBoolean());
        assertEquals(0, options.get("tie_noise").asDouble());
        assertEquals("entity_alignment", replay.getMethod());
        assertTrue(replay.toString().startsWith("StudyReplayEntityAlignmentMatcher("));
    }
}
