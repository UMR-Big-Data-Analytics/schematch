package de.uni_marburg.schematch.matching.sota;

import de.uni_marburg.schematch.matching.Matcher;
import de.uni_marburg.schematch.matchtask.MatchTask;
import de.uni_marburg.schematch.matchtask.matchstep.MatchingStep;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * EmbDI (Cappuzzo, Papotti &amp; Thirumuruganathan, SIGMOD 2020) through its upstream Schematch
 * server, ported from the upstream fork's {@code matching/sota/EmbdiMatcher} (code/prisma).
 *
 * <p>The server is upstream's {@code src/main/resources/embdi/embdi_server.py} (with its own
 * requirements and Dockerfile, code/prisma/src/main/resources/embdi); it is <b>not installed</b>
 * in this environment. Protocol, unchanged from upstream:
 * {@code GET http://127.0.0.1:<serverPort>/match?scenario_path=..&scenario_name=..&sm_mode=..},
 * response one matrix row per line, space-separated. Unlike upstream, a failure raises instead of
 * returning an empty matrix (see PythonServerMatcher).
 */
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class EmbdiMatcher extends Matcher {
    private Integer serverPort = 5001;
    private String sm_mode = "binary_from_embdi"; // or dot_product_similarity

    @Override
    public float[][] match(MatchTask matchTask, MatchingStep matchStep) {
        String uri = "http://127.0.0.1:" + serverPort + "/match"
                + "?scenario_path=" + URLEncoder.encode(matchTask.getScenario().getPath(), StandardCharsets.UTF_8)
                + "&scenario_name=" + URLEncoder.encode(matchTask.getScenario().getName(), StandardCharsets.UTF_8)
                + "&sm_mode=" + URLEncoder.encode(sm_mode, StandardCharsets.UTF_8);
        try {
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                    .send(HttpRequest.newBuilder(URI.create(uri)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("EmbDI server returned HTTP " + response.statusCode());
            }
            float[][] sim = matchTask.getEmptySimMatrix();
            String[] lines = response.body().strip().split("\\R");
            if (lines.length != sim.length) {
                throw new IllegalStateException("EmbDI returned " + lines.length + " rows, expected " + sim.length);
            }
            for (int i = 0; i < sim.length; i++) {
                String[] cells = lines[i].trim().split("\\s+");
                if (cells.length != sim[i].length) {
                    throw new IllegalStateException("EmbDI row " + i + " has " + cells.length + " scores");
                }
                for (int j = 0; j < cells.length; j++) {
                    sim[i][j] = Float.parseFloat(cells[j]);
                }
            }
            return sim;
        } catch (Exception e) {
            throw new RuntimeException("EmbDI matcher failed (is the upstream EmbDI server running on port "
                    + serverPort + "?)", e);
        }
    }
}
