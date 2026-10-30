package de.uni_marburg.schematch.matching.ensemble;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.matchtask.MatchTask;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import de.uni_marburg.schematch.utils.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StudyRecipesTest {
    @TempDir Path dir;
    ObjectMapper json = new ObjectMapper();

    @Test
    void weightedSumKeepsAbsoluteScaleAndNestedRecipesNormalizeOnlyLeaves() throws Exception {
        assertEquals(.86, CompositeMatcher.aggregate(List.of(new float[][]{{.8f}},new float[][]{{.6f}}),
                new double[]{1,.1},"SUM")[0][0], 1e-6);
        assertEquals("NONE", new C3Matcher().getNormalise());
        assertEquals("NONE", new IVPlusValueMatcher().getNormalise());
        assertEquals("SUM", new IVPlusValueMatcher().getAggregation());
    }

    // Add only transport ports to a recipe; leave its weights, composition and normalization untouched.
    void setPort(CompositeMatcher matcher, int port) throws Exception {
        JsonNode components = json.readTree(matcher.getComponents());
        for (JsonNode component : components) {
            ObjectNode spec = (ObjectNode) component;
            if (spec.get("package").asText().equals("external")) spec.with("params").put("serverPort",port);
            else if (spec.get("package").asText().equals("ensemble")) {
                CompositeMatcher nested = (CompositeMatcher) CompositeMatcher.components("[" + spec + "]").get(0).matcher();
                setPort(nested,port);
                spec.with("params").put("components", nested.getComponents());
            }
        }
        matcher.setComponents(json.writeValueAsString(components));
    }

    @Test
    void recipesCombineServedSeedsAndLiveNativeValuesWithStudyArithmetic() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/match", exchange -> {
            String method = json.readTree(exchange.getRequestBody()).get("method").asText();
            String matrix = switch (method) {
                case "coma" -> "0.1 0.3\n0.2 0.4\n";
                case "char3_soft" -> "0.2 0.6\n0.4 0.8\n";
                case "cupid" -> "0.9 0.2\n0.3 0.1\n";
                case "sbert" -> "-0.2 0.8\n0.3 0.3\n";
                default -> throw new IllegalArgumentException(method);
            };
            byte[] bytes = matrix.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            Files.createDirectories(dir.resolve("source"));
            Files.createDirectories(dir.resolve("target"));
            Files.writeString(dir.resolve("source/s.csv"), "c0,c1\na,0\na,1\nb,2\nb,3\n");
            Files.writeString(dir.resolve("target/t.csv"), "d0,d1\nx,0\nx,1\ny,2\ny,3\n");
            Configuration config = Configuration.getInstance();
            boolean old = config.isReadDependencies();
            Scenario scenario;
            try { config.setReadDependencies(false); scenario = new Scenario(dir.toString()); }
            finally { config.setReadDependencies(old); }
            MatchTask task = new MatchTask(null,scenario,List.of(),List.of());
            task.setTablePairs(List.of(new TablePair(scenario.getSourceDatabase().getTables().get(0),
                    scenario.getTargetDatabase().getTables().get(0))));
            float[][] val = new ValueAverageMatcher().match(task,null);
            assertArrayEquals(new float[]{1,0},val[0],1e-6f);
            assertArrayEquals(new float[]{0,1},val[1],1e-6f);
            IVMatcher iv = new IVMatcher();
            setPort(iv,server.getAddress().getPort());
            float[][] scores = iv.match(task,null);
            assertArrayEquals(new float[]{.25f,.6145833333f},scores[0],1e-6f);
            assertArrayEquals(new float[]{.3541666667f,.625f},scores[1],1e-6f);
            IVPlusValueMatcher plus = new IVPlusValueMatcher();
            setPort(plus,server.getAddress().getPort());
            assertArrayEquals(new float[]{.35f,.6145833333f},plus.match(task,null)[0],1e-6f);
            assertArrayEquals(new float[]{.3541666667f,.725f},plus.match(task,null)[1],1e-6f);
            C3Matcher c3 = new C3Matcher();
            setPort(c3,server.getAddress().getPort());
            assertArrayEquals(new float[]{1f/3,2f/9},c3.match(task,null)[0],1e-6f);
            assertArrayEquals(new float[]{1f/9,1},c3.match(task,null)[1],1e-6f);
        } finally { server.stop(0); }
    }
}
