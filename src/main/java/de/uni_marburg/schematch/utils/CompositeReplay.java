package de.uni_marburg.schematch.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.matching.Matcher;
import de.uni_marburg.schematch.matching.MatcherFactory;
import de.uni_marburg.schematch.matching.MatrixRefinementMatcher;
import de.uni_marburg.schematch.matching.ensemble.CompositeMatcher;
import de.uni_marburg.schematch.matchtask.MatchTask;
import de.uni_marburg.schematch.matchtask.matchstep.MatchingStep;
import de.uni_marburg.schematch.matchtask.tablepair.generators.NaiveTablePairsGenerator;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs scenario-level matchers (composites, presets, refinements, probabilistic ensembles) in a minimal pipeline: one
 * MatchTask with all table pairs (NaiveTablePairsGenerator) and a first-line MatchingStep, exactly as Main would call
 * them. For tools/check_composite_regression.py.
 *
 * <pre>
 * java -cp target/classes;&lt;deps&gt; de.uni_marburg.schematch.utils.CompositeReplay &lt;scenarioPath&gt; &lt;seedRoot&gt; &lt;cases.json&gt; &lt;outDir&gt;
 * </pre>
 *
 * cases.json: a list of {"key", "spec": {"name", "package", "params"}, "substitute": [{"name", "params"?, "seed"}, ...]}.
 * Before running, every component of a composite (recursively, through presets such as IVMatcher and through a
 * refinement's inline {@code base}) that matches a substitution rule is replaced by a {@code FileSeedMatcher} reading the
 * stored seed. Rules are tried in order; a rule matches if the names are equal and every parameter it lists has the same
 * value in the component. Without rules, components are computed. Output: &lt;outDir&gt;/&lt;key&gt;.tsv as MatcherReplay, plus
 * status.tsv.
 */
public final class CompositeReplay {
    private static final ObjectMapper JSON = new ObjectMapper();

    private CompositeReplay() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: <scenarioPath> <seedRoot> <cases.json> <outDir>");
            System.exit(2);
        }
        Configuration.getInstance().setReadDependencies(false);
        Scenario scenario = new Scenario(args[0]);
        String seedRoot = args[1];
        List<Map<String, Object>> cases = JSON.readValue(new File(args[2]), new TypeReference<>() {
        });
        Path out = Path.of(args[3]);
        Files.createDirectories(out);
        MatcherFactory factory = new MatcherFactory();
        try (PrintWriter status = new PrintWriter(Files.newBufferedWriter(out.resolve("status.tsv"), StandardCharsets.UTF_8))) {
            for (Map<String, Object> c : cases) {
                String key = String.valueOf(c.get("key"));
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> spec = (Map<String, Object>) c.get("spec");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rules = c.get("substitute") == null ? List.of()
                            : (List<Map<String, Object>>) c.get("substitute");
                    Map<String, Object> resolved = substitute(spec, rules, seedRoot, factory);
                    Matcher m = factory.createMatcherInstance(new Configuration.MatcherConfiguration(
                            (String) resolved.get("name"), (String) resolved.get("package"), params(resolved)));
                    MatchTask task = new MatchTask(null, scenario, List.of(), List.of());
                    task.setTablePairs(new NaiveTablePairsGenerator().generateCandidates(scenario));
                    MatchingStep step = new MatchingStep(false, false, 1, List.of(m));
                    float[][] sim = m.match(task, step);
                    MatcherReplay.write(out.resolve(key + ".tsv"), scenario.getSourceDatabase(), scenario.getTargetDatabase(), sim);
                    status.println(key + "\tok\t" + JSON.writeValueAsString(resolved).replace('\t', ' '));
                } catch (Throwable e) {
                    status.println(key + "\tfailed\t" + e.getClass().getSimpleName() + ": "
                            + String.valueOf(e.getMessage()).replace('\t', ' ').replace('\n', ' '));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(Map<String, Object> spec) {
        Object p = spec.get("params");
        return p instanceof Map ? new LinkedHashMap<>((Map<String, Object>) p) : new LinkedHashMap<>();
    }

    private static boolean matches(Map<String, Object> rule, Map<String, Object> spec) {
        if (!String.valueOf(rule.get("name")).equals(spec.get("name"))) {
            return false;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> rp = rule.get("params") instanceof Map ? (Map<String, Object>) rule.get("params") : Map.of();
        Map<String, Object> sp = params(spec);
        for (Map.Entry<String, Object> e : rp.entrySet()) {
            if (!String.valueOf(e.getValue()).equals(String.valueOf(sp.get(e.getKey())))) {
                return false;
            }
        }
        return true;
    }

    /** Returns a spec in which matching components are FileSeedMatchers; presets are expanded into CompositeMatchers. */
    static Map<String, Object> substitute(Map<String, Object> spec, List<Map<String, Object>> rules, String seedRoot,
                                          MatcherFactory factory) throws Exception {
        for (Map<String, Object> rule : rules) {
            if (matches(rule, spec)) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("name", "FileSeedMatcher");
                out.put("package", "control");
                out.put("params", Map.of("seedRoot", seedRoot, "seed", String.valueOf(rule.get("seed"))));
                if (spec.containsKey("weight")) {
                    out.put("weight", spec.get("weight"));
                }
                return out;
            }
        }
        if (rules.isEmpty()) {
            return spec;
        }
        Matcher m = factory.createMatcherInstance(new Configuration.MatcherConfiguration(
                (String) spec.get("name"), (String) spec.get("package"), params(spec)));
        if (m instanceof CompositeMatcher cm) {
            List<Map<String, Object>> comps = JSON.readValue(cm.getComponents(), new TypeReference<>() {
            });
            List<Map<String, Object>> sub = new ArrayList<>();
            for (Map<String, Object> comp : comps) {
                sub.add(substitute(comp, rules, seedRoot, factory));
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("components", JSON.writeValueAsString(sub));
            p.put("aggregation", cm.getAggregation());
            p.put("normalise", cm.getNormalise());
            p.put("predictor", cm.getPredictor());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", "CompositeMatcher");
            out.put("package", "ensemble");
            out.put("params", p);
            if (spec.containsKey("weight")) {
                out.put("weight", spec.get("weight"));
            }
            return out;
        }
        if (m instanceof MatrixRefinementMatcher rm && rm.getBase() != null && !rm.getBase().isBlank()) {
            Map<String, Object> base = JSON.readValue(rm.getBase(), new TypeReference<>() {
            });
            Map<String, Object> p = params(spec);
            p.put("base", JSON.writeValueAsString(substitute(base, rules, seedRoot, factory)));
            Map<String, Object> out = new LinkedHashMap<>(spec);
            out.put("params", p);
            return out;
        }
        return spec;
    }
}
