package de.uni_marburg.schematch.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.uni_marburg.schematch.matching.Matcher;
import de.uni_marburg.schematch.matching.MatrixRefinementMatcher;
import de.uni_marburg.schematch.matching.external.ScenarioPythonServerMatcher;
import de.uni_marburg.schematch.matching.similarityFlooding.SimilarityFlooding;
import de.uni_marburg.schematch.matchtask.MatchTask;
import de.uni_marburg.schematch.matchtask.matchstep.MatchingStep;
import de.uni_marburg.schematch.utils.Configuration;
import de.uni_marburg.schematch.utils.GroundTruthReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Captures raw matrices before boosting and evaluates them once per run with the paper's exact Python functions. */
public final class PaperEvaluationRecorder {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static Path input, directory;
    private PaperEvaluationRecorder() {}

    public static void start(Path runDirectory) throws Exception {
        Configuration config = Configuration.getInstance();
        if (!config.isPaperEvaluation()) return;
        if (config.getPaperEvaluationBootstrap() < 0 || config.getPaperEvaluationMinFamilySize() < 1
                || config.getPaperEvaluationTimeoutSeconds() < 1) throw new IllegalArgumentException("Invalid paper evaluation settings");
        if (config.getPaperEvaluationBootstrap() > 0 && config.getPaperEvaluationGroups().isBlank())
            throw new IllegalArgumentException("Bootstrap evaluation requires an explicit family/unit grouping manifest");
        if (!config.getPaperEvaluationGroups().isBlank() && !Files.isRegularFile(Path.of(config.getPaperEvaluationGroups())))
            throw new IllegalArgumentException("Missing paper evaluation grouping manifest: " + config.getPaperEvaluationGroups());
        Path script = Path.of(config.getPaperEvaluationScript()).toAbsolutePath();
        if (!Files.isRegularFile(script)) throw new IllegalArgumentException("Missing paper evaluator: " + script);
        runProcess(List.of(config.getPaperEvaluationPython(),"-c","import numpy, scipy"),30);
        directory = runDirectory.resolve("paper_evaluation").toAbsolutePath();
        Files.createDirectories(directory);
        input = directory.resolve("input.jsonl");
        Files.writeString(input,"",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }

    public static synchronized void record(MatchTask task, MatchingStep step, Matcher matcher, double wallMs, boolean fromCache) {
        if (!Configuration.getInstance().isPaperEvaluation()) return;
        if (input == null) throw new IllegalStateException("PaperEvaluationRecorder.start must run before matching");
        Map<String,Object> record = new LinkedHashMap<>();
        record.put("dataset",task.getDataset() == null ? Path.of(task.getScenario().getPath()).getParent().getFileName().toString() : task.getDataset().getName());
        record.put("scenario",task.getScenario().getName());
        record.put("stage",step.toString()); record.put("matcher",matcher.toString());
        record.put("baseline",baseline(task,step,matcher)); record.put("wall_ms",wallMs); record.put("from_cache",fromCache);
        record.put("source_labels",GroundTruthReader.columns(task.getScenario().getSourceDatabase()).stream().map(GroundTruthReader.ColumnRef::fullName).toList());
        record.put("target_labels",GroundTruthReader.columns(task.getScenario().getTargetDatabase()).stream().map(GroundTruthReader.ColumnRef::fullName).toList());
        float[][] scores = task.getSimMatrix(step,matcher);
        int[][] gt = task.getGroundTruthMatrix();
        if (gt == null || scores.length != gt.length) throw new IllegalArgumentException("Missing or misaligned evaluation truth");
        for (int i = 0; i < scores.length; i++) {
            if (scores[i].length != gt[i].length) throw new IllegalArgumentException("Score/truth dimensions differ");
            for (float score : scores[i]) if (!Float.isFinite(score)) throw new IllegalArgumentException("Non-finite evaluation score");
        }
        record.put("scores",scores); record.put("ground_truth",gt);
        try { Files.writeString(input,JSON.writeValueAsString(record)+"\n",StandardCharsets.UTF_8,StandardOpenOption.APPEND); }
        catch (IOException e) { throw new IllegalStateException("Cannot record paper evaluation",e); }
    }

    private static String baseline(MatchTask task, MatchingStep step, Matcher matcher) {
        if (step.getLine() == 1) return matcher.toString();
        List<Matcher> first = task.getFirstLineMatchers();
        Integer index = null;
        if (matcher instanceof ScenarioPythonServerMatcher bridge) {
            if (!"RAW_FIRST_LINE".equalsIgnoreCase(bridge.getInitialMatrix())) return "";
            index = bridge.getBaseMatcherIndex();
        } else if (matcher instanceof MatrixRefinementMatcher refinement) {
            if (!refinement.getBase().isBlank() || !"RAW_FIRST_LINE".equalsIgnoreCase(refinement.getInitialMatrix())) return "";
            index = refinement.getBaseMatcherIndex();
        } else if (matcher instanceof SimilarityFlooding sf && "RAW_FIRST_LINE".equalsIgnoreCase(sf.getInitialMatrix())) index = 0;
        return first != null && index != null && index >= 0 && index < first.size() ? first.get(index).toString() : "";
    }

    public static void finish() throws Exception {
        Configuration config = Configuration.getInstance();
        if (!config.isPaperEvaluation()) return;
        if (input == null) throw new IllegalStateException("Paper evaluation was not started");
        List<String> command = new ArrayList<>(List.of(config.getPaperEvaluationPython(),config.getPaperEvaluationScript(),
                "--records",input.toString(),"--out",directory.toString(),"--min-family-size",String.valueOf(config.getPaperEvaluationMinFamilySize()),
                "--bootstrap",String.valueOf(config.getPaperEvaluationBootstrap()),"--seed",String.valueOf(config.getPaperEvaluationBootstrapSeed())));
        if (!config.getPaperEvaluationGroups().isBlank()) command.addAll(List.of("--groups",config.getPaperEvaluationGroups()));
        try { runProcess(command,config.getPaperEvaluationTimeoutSeconds()); }
        finally { input = null; directory = null; }
    }

    private static void runProcess(List<String> command, int timeoutSeconds) throws Exception {
        Process process = new ProcessBuilder(command).inheritIO().start();
        if (!process.waitFor(timeoutSeconds,TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Paper evaluation timed out");
        }
        if (process.exitValue() != 0) throw new IllegalStateException("Paper evaluation failed (exit " + process.exitValue()
                + "); check the configured Python/NumPy/SciPy environment and evaluator output");
    }
}
