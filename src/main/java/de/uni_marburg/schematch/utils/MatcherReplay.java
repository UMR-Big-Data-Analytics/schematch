package de.uni_marburg.schematch.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.uni_marburg.schematch.data.Database;
import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.matching.Matcher;
import de.uni_marburg.schematch.matching.TablePairMatcher;
import de.uni_marburg.schematch.matching.TokenizedTablePairMatcher;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;

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
 * Replays first-line Java matchers on one real scenario, outside the pipeline, for the seed-regression check
 * (tools/check_seed_regression.py). Schematch reads the scenario exactly as in a run (InputReader), every table pair is
 * matched, and the full source x target matrix is written with its labels.
 *
 * <pre>
 * java -cp target/classes;&lt;deps&gt; de.uni_marburg.schematch.utils.MatcherReplay &lt;scenarioPath&gt; &lt;cases.json&gt; &lt;outDir&gt;
 * </pre>
 *
 * cases.json: a list of {"seed", "name", "package", "params"} (the shape of tools/matcher_seeds.json). For each case the
 * file &lt;outDir&gt;/&lt;seed&gt;.tsv holds the source labels, the target labels (tab-separated, "table.column") and one matrix
 * row per line. Matchers that are not table-pair matchers, or need a tokenizer, are reported as skipped in
 * &lt;outDir&gt;/status.tsv, as are failures (with the exception message).
 */
public final class MatcherReplay {
    private static final ObjectMapper JSON = new ObjectMapper();

    private MatcherReplay() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: <scenarioPath> <cases.json> <outDir>");
            System.exit(2);
        }
        Configuration.getInstance().setReadDependencies(false);
        Scenario scenario = new Scenario(args[0]);
        List<Map<String, Object>> cases = JSON.readValue(new File(args[1]), new TypeReference<>() {
        });
        Path out = Path.of(args[2]);
        Files.createDirectories(out);
        Database source = scenario.getSourceDatabase();
        Database target = scenario.getTargetDatabase();
        try (PrintWriter status = new PrintWriter(Files.newBufferedWriter(out.resolve("status.tsv"), StandardCharsets.UTF_8))) {
            for (Map<String, Object> c : cases) {
                String seed = String.valueOf(c.get("seed"));
                try {
                    Matcher m = instantiate(String.valueOf(c.get("package")), String.valueOf(c.get("name")),
                            castParams(c.get("params")));
                    if (!(m instanceof TablePairMatcher tpm) || m instanceof TokenizedTablePairMatcher) {
                        status.println(seed + "\tskipped\tnot a plain table-pair matcher");
                        continue;
                    }
                    float[][] sim = new float[source.getNumColumns()][target.getNumColumns()];
                    for (Table s : source.getTables()) {
                        for (Table t : target.getTables()) {
                            float[][] part = tpm.match(new TablePair(s, t));
                            for (int i = 0; i < part.length; i++) {
                                System.arraycopy(part[i], 0, sim[s.getOffset() + i], t.getOffset(), part[i].length);
                            }
                        }
                    }
                    write(out.resolve(seed + ".tsv"), source, target, sim);
                    status.println(seed + "\tok\t");
                } catch (Throwable e) {
                    status.println(seed + "\tfailed\t" + e.getClass().getSimpleName() + ": "
                            + String.valueOf(e.getMessage()).replace('\t', ' ').replace('\n', ' '));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castParams(Object params) {
        return params instanceof Map ? new LinkedHashMap<>((Map<String, Object>) params) : new LinkedHashMap<>();
    }

    private static Matcher instantiate(String packageName, String name, Map<String, Object> params) throws Exception {
        Class<?> cls = Class.forName(Configuration.MATCHING_PACKAGE_NAME + "." + packageName + "." + name);
        Matcher m = (Matcher) cls.getDeclaredConstructor().newInstance();
        Configuration.MatcherConfiguration mc = new Configuration.MatcherConfiguration();
        mc.setName(name);
        mc.setPackageName(packageName);
        mc.setParams(params);
        m.configure(mc);
        return m;
    }

    static void write(Path file, Database source, Database target, float[][] sim) throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            w.println(String.join("\t", labels(source)));
            w.println(String.join("\t", labels(target)));
            for (float[] row : sim) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < row.length; j++) {
                    if (j > 0) {
                        sb.append(' ');
                    }
                    sb.append(row[j]);
                }
                w.println(sb);
            }
        }
    }

    private static List<String> labels(Database db) {
        List<String> out = new ArrayList<>(db.getNumColumns());
        for (int i = 0; i < db.getNumColumns(); i++) {
            out.add(db.getFullColumnNameByIndex(i));
        }
        return out;
    }
}
