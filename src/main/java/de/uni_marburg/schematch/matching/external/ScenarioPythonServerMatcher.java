package de.uni_marburg.schematch.matching.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.uni_marburg.schematch.data.Database;
import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.matching.Matcher;
import de.uni_marburg.schematch.matchtask.MatchTask;
import de.uni_marburg.schematch.matchtask.matchstep.MatchingStep;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls a scenario-level matcher that lives in Python, one request per scenario, so that methods which need the whole
 * schema (all tables, foreign keys, dependency profiles) run inside Schematch. {@link PythonServerMatcher} sends one table
 * pair at a time and cannot do this.
 *
 * <p>The reference server is {@code src/main/resources/python_matchers/study_server.py}. It serves the follow-up study's
 * matchers with their frozen configurations: {@code base}, {@code q} (table-qualified names), {@code dep} (X105) and
 * {@code entity_alignment} (X109, greedy solver by default). Request body, to {@code POST http://127.0.0.1:<port>/match}:
 * {@code {"method", "options", "scenario_path", "source_labels", "target_labels", "base"}}, with labels as
 * {@code table.column} in Schematch's column order. The response is one matrix row per line, as for
 * {@link PythonServerMatcher}.
 *
 * <p>{@code initialMatrix} chooses the base matrix, as for SimilarityFlooding:
 * {@code PREVIOUS} (the previous match step's matrix of the first-line matcher at {@code baseMatcherIndex}),
 * {@code RAW_FIRST_LINE} (that matcher's raw first-line matrix), or {@code SERVER} (no matrix is sent; the server composes
 * the base from the study's seed files according to {@code options}, e.g.
 * {@code {"base_recipe": "iv+0.1val", "seed_root": ".../reference/seeds/test_plain"}}).
 *
 * <p>Use it as a second-line matcher. A failed call raises unless {@code failOnError=false}: an empty matrix would look
 * like a valid, uninformative result.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ScenarioPythonServerMatcher extends Matcher {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private Integer serverPort = 5102;
    private String method = "entity_alignment";
    private String options = "{}";
    private String initialMatrix = "PREVIOUS";
    private Integer baseMatcherIndex = 0;
    private Integer timeoutSeconds = 3600;
    private Boolean failOnError = true;

    @Override
    public float[][] match(MatchTask matchTask, MatchingStep matchStep) {
        try {
            return matchScenario(matchTask.getScenario(), base(matchTask, matchStep));
        } catch (Exception e) {
            if (Boolean.FALSE.equals(failOnError)) {
                getLogger().error("Study matcher '{}' failed for {}; returning an empty matrix", method,
                        matchTask.getScenario().getPath(), e);
                return matchTask.getEmptySimMatrix();
            }
            throw new RuntimeException("Study matcher '" + method + "' failed for " + matchTask.getScenario().getPath()
                    + " (is study_server.py running on port " + serverPort + "?)", e);
        }
    }

    /**
     * One request for a whole scenario. {@code base} may be null (initialMatrix SERVER). Exposed so that the wrapper can
     * be checked against the Python side without a full Schematch run (ScenarioPythonServerMatcherCheck).
     */
    public float[][] matchScenario(Scenario scenario, float[][] base) throws Exception {
        Database source = scenario.getSourceDatabase();
        Database target = scenario.getTargetDatabase();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("method", method);
        body.put("options", JSON.readTree(options == null || options.isBlank() ? "{}" : options));
        body.put("scenario_path", scenario.getPath());
        body.put("source_labels", labels(source));
        body.put("target_labels", labels(target));
        body.put("base", base);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/match"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Study matcher '" + method + "' returned HTTP "
                    + response.statusCode() + ": " + response.body());
        }
        return PythonServerMatcher.parse(response.body(), new float[source.getNumColumns()][target.getNumColumns()]);
    }

    /** Full column names {@code table.column} in matrix order (the database's column index order). */
    static List<String> labels(Database database) {
        List<String> out = new ArrayList<>(database.getNumColumns());
        for (int i = 0; i < database.getNumColumns(); i++) {
            out.add(database.getFullColumnNameByIndex(i));
        }
        return out;
    }

    private float[][] base(MatchTask matchTask, MatchingStep matchStep) {
        if ("SERVER".equalsIgnoreCase(initialMatrix)) {
            return null;
        }
        List<Matcher> firstLine = matchTask.getFirstLineMatchers();
        if (firstLine.isEmpty() || baseMatcherIndex < 0 || baseMatcherIndex >= firstLine.size()) {
            throw new IllegalArgumentException("baseMatcherIndex " + baseMatcherIndex + " out of range for "
                    + firstLine.size() + " first-line matchers");
        }
        Matcher baseMatcher = firstLine.get(baseMatcherIndex);
        return switch (initialMatrix.toUpperCase()) {
            case "PREVIOUS" -> matchTask.getSimMatrixFromPreviousMatchStep(matchStep, baseMatcher);
            case "RAW_FIRST_LINE" -> matchTask.getFirstLineSimMatrix(baseMatcher);
            default -> throw new IllegalArgumentException("Unknown initialMatrix: " + initialMatrix
                    + " (PREVIOUS, RAW_FIRST_LINE or SERVER)");
        };
    }
}
