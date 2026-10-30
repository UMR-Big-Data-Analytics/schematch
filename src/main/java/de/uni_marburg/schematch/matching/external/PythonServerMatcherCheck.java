package de.uni_marburg.schematch.matching.external;

import de.uni_marburg.schematch.data.Scenario;
import de.uni_marburg.schematch.matching.Matcher;
import de.uni_marburg.schematch.matching.MatcherFactory;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import de.uni_marburg.schematch.utils.Configuration;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Smoke check for table-pair matchers (the seed-server presets by default) without a full Schematch run: reads one single-table scenario the way
 * Schematch does, runs the named preset (package {@code external}) on its first source and target table against
 * seed_server.py, and writes the returned matrix plus the label order to a file for comparison with the Python side.
 *
 * <pre>
 * java -cp target/classes;&lt;deps&gt; de.uni_marburg.schematch.matching.external.PythonServerMatcherCheck \
 *     &lt;scenarioPath&gt; &lt;port&gt; &lt;presetClass&gt; &lt;optionsJson&gt; &lt;outFile&gt;
 * </pre>
 */
public final class PythonServerMatcherCheck {
    private PythonServerMatcherCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5 && args.length != 7) {
            System.err.println("usage: <scenarioPath> <port> <presetClass> <optionsJson> <outFile> [<package> <paramsJson>]");
            System.exit(2);
        }
        Configuration.getInstance().setReadDependencies(false);
        Scenario scenario = new Scenario(args[0]);
        // Optional package and parameters: any table-pair matcher (e.g. a Java preset against its parameterised parent).
        Map<String, Object> params = args.length == 7
                ? new com.fasterxml.jackson.databind.ObjectMapper().readValue(args[6],
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { })
                : Map.of("serverPort", Integer.parseInt(args[1]), "options", args[3]);
        Matcher matcher = new MatcherFactory().createMatcherInstance(new Configuration.MatcherConfiguration(
                args[2], args.length == 7 ? args[5] : "external", new java.util.HashMap<>(params)));
        TablePair pair = new TablePair(scenario.getSourceDatabase().getTables().get(0),
                scenario.getTargetDatabase().getTables().get(0));
        float[][] sim = ((de.uni_marburg.schematch.matching.TablePairMatcher) matcher).match(pair);
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[4]), StandardCharsets.UTF_8))) {
            out.println(String.join("\t", pair.getSourceTable().getLabels()));
            out.println(String.join("\t", pair.getTargetTable().getLabels()));
            for (float[] row : sim) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < row.length; j++) {
                    if (j > 0) {
                        sb.append(' ');
                    }
                    sb.append(row[j]);
                }
                out.println(sb);
            }
        }
        System.out.println(matcher + ": wrote " + sim.length + " x " + (sim.length == 0 ? 0 : sim[0].length) + " to " + args[4]);
    }
}
