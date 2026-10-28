package de.uni_marburg.schematch.matching.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.uni_marburg.schematch.matching.TablePairMatcher;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calls a matcher that lives in Python (embeddings, LLMs, Valentine's Python matchers, Magneto,
 * ...) from Schematch, so that it is an ordinary first-line matcher and therefore an SF seed via
 * {@code initialMatrix: PREVIOUS / RAW_FIRST_LINE}.
 *
 * <p>The protocol follows upstream Schematch's EmbDI and PRISMA matchers (a local HTTP server, one
 * request per table pair, the response is one matrix row per line with space-separated scores), but
 * sends a JSON body: {@code {"method", "options", "source_path", "target_path", "source_table",
 * "target_table", "source_labels", "target_labels"}} to {@code POST http://127.0.0.1:<port>/match}.
 * The reference server is {@code src/main/resources/python_matchers/seed_server.py}.
 *
 * <p>A failed call raises rather than returning an empty matrix: an all-zero seed floods into a
 * complete, plausible-looking, meaningless SF run (harness FINDINGS 71). {@code failOnError=false}
 * restores upstream's fallback to an empty matrix.
 *
 * <p>{@code method}: the server-side matcher name. {@code options}: a JSON object passed through
 * verbatim, e.g. {@code {"model": "all-mpnet-base-v2"}}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PythonServerMatcher extends TablePairMatcher {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private Integer serverPort = 5101;
    private String method = "sbert";
    private String options = "{}";
    private Integer timeoutSeconds = 3600;
    private Boolean failOnError = true;

    @Override
    public float[][] match(TablePair tablePair) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("method", method);
            body.put("options", JSON.readTree(options == null || options.isBlank() ? "{}" : options));
            body.put("source_path", tablePair.getSourceTable().getPath());
            body.put("target_path", tablePair.getTargetTable().getPath());
            body.put("source_table", tablePair.getSourceTable().getName());
            body.put("target_table", tablePair.getTargetTable().getName());
            body.put("source_labels", tablePair.getSourceTable().getLabels());
            body.put("target_labels", tablePair.getTargetTable().getLabels());
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/match"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Python matcher '" + method + "' returned HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            return parse(response.body(), tablePair.getEmptySimMatrix());
        } catch (Exception e) {
            if (Boolean.FALSE.equals(failOnError)) {
                getLogger().error("Python matcher '{}' failed for {}; returning an empty matrix", method, tablePair, e);
                return tablePair.getEmptySimMatrix();
            }
            throw new RuntimeException("Python matcher '" + method + "' failed for " + tablePair
                    + " (is seed_server.py running on port " + serverPort + "?)", e);
        }
    }

    static float[][] parse(String body, float[][] sim) {
        String[] lines = body.strip().split("\\R");
        if (lines.length != sim.length && !(sim.length == 0 && body.isBlank())) {
            throw new IllegalStateException("Expected " + sim.length + " rows, got " + lines.length);
        }
        for (int i = 0; i < sim.length; i++) {
            String[] cells = lines[i].trim().split("\\s+");
            if (cells.length != sim[i].length) {
                throw new IllegalStateException("Row " + i + ": expected " + sim[i].length
                        + " scores, got " + cells.length);
            }
            for (int j = 0; j < cells.length; j++) {
                float v = Float.parseFloat(cells[j]);
                if (!Float.isFinite(v)) {
                    throw new IllegalStateException("Non-finite score at (" + i + "," + j + ")");
                }
                sim[i][j] = v;
            }
        }
        return sim;
    }
}
