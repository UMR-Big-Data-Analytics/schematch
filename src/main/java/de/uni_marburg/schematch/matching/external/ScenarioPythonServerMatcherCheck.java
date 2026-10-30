package de.uni_marburg.schematch.matching.external;

import de.uni_marburg.schematch.data.Scenario;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Smoke check for {@link ScenarioPythonServerMatcher} without a full Schematch run: reads one scenario the way Schematch
 * does, sends it to study_server.py with {@code initialMatrix=SERVER}, and writes the returned matrix plus the label
 * order to a file, so that test_study_server_java.py can compare it with the Python side.
 *
 * <pre>
 * java -cp target/classes;&lt;deps&gt; de.uni_marburg.schematch.matching.external.ScenarioPythonServerMatcherCheck \
 *     &lt;scenarioPath&gt; &lt;port&gt; &lt;method&gt; &lt;optionsJson&gt; &lt;outFile&gt;
 * </pre>
 */
public final class ScenarioPythonServerMatcherCheck {
    private ScenarioPythonServerMatcherCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("usage: <scenarioPath> <port> <method> <optionsJson> <outFile>");
            System.exit(2);
        }
        // This transport check reads existing data; profile discovery is not part of it.
        de.uni_marburg.schematch.utils.Configuration.getInstance().setReadDependencies(false);
        Scenario scenario = new Scenario(args[0]);
        ScenarioPythonServerMatcher m = new ScenarioPythonServerMatcher();
        m.setServerPort(Integer.parseInt(args[1]));
        m.setMethod(args[2]);
        m.setOptions(args[3]);
        m.setInitialMatrix("SERVER");
        float[][] sim = m.matchScenario(scenario, null);
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[4]), StandardCharsets.UTF_8))) {
            out.println(String.join("\t", ScenarioPythonServerMatcher.labels(scenario.getSourceDatabase())));
            out.println(String.join("\t", ScenarioPythonServerMatcher.labels(scenario.getTargetDatabase())));
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
        System.out.println("wrote " + sim.length + " x " + (sim.length == 0 ? 0 : sim[0].length) + " to " + args[4]);
    }
}
