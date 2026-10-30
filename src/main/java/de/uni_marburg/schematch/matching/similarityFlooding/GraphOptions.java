package de.uni_marburg.schematch.matching.similarityFlooding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Graph-construction and dependency settings of one SF run.
 *
 * <p>Each value is the matcher parameter of the same name where one is set, and otherwise the JVM system property
 * that held it before it was a parameter ({@code smx.sf.*}, parsed exactly as before). Without parameters the run is
 * therefore the one the properties describe, and {@link #defaults()} is that run. The parameter names are the property
 * names without the {@code smx.sf.} prefix, with {@code gate.high} etc. written {@code gateHigh}.
 */
final class GraphOptions {
    /** Constraint weighting (SchemaGraphBuilder): CONSTANT, TOPK_HYBRID and GATED. */
    final double constantWeight;
    final int topK;
    final double topKPercent;
    final double topkHigh;
    final double topkLow;
    final double gateHigh;
    final double gateLow;
    final double gateIncrease;
    final double gateDecrease;
    /** Schema-graph construction. */
    final boolean canonicalDependencyOrder;
    final String collisionRule;
    final String parallelEdgeRule;
    final String parallelEdges;
    final String extraEdgesDir;
    final String tableMembership;
    final int tableMembershipDraw;
    final boolean tableRooted;
    final boolean noDatatypeNodes;
    final boolean uccNoNotUnique;
    final boolean uccUnweighted;
    /** Dependency selection and perturbation (DependencyFilter). */
    final String uccTopK;
    final double fdRetention;
    final long fdRetentionSeed;
    final boolean fdRewire;
    final String fdRewireMode;
    final double fdWTarget;
    /** Native graph information (no property fallback): class nodes and within-table edges. */
    final List<String> classNodes;
    final WithinTableEdges.Spec withinTable;

    /** The settings the system properties describe; what every caller without parameters uses. */
    static GraphOptions defaults() {
        return resolve(Map.of());
    }

    static final List<String> CLASS_NODES = List.of("SHAPE", "SEMTYPE", "SHAPE_SHUFFLED", "SEMTYPE_SHUFFLED");

    private GraphOptions(Map<String, String> p) {
        constantWeight = Double.parseDouble(value(p, "constantWeight", "smx.sf.constantWeight", "0.35"));
        topK = p.get("topK") != null ? Integer.parseInt(p.get("topK").trim()) : Integer.getInteger("smx.sf.topK", 1);
        topKPercent = Double.parseDouble(value(p, "topKPercent", "smx.sf.topKPercent", "0"));
        topkHigh = Double.parseDouble(value(p, "topkHigh", "smx.sf.topkHigh", "0.20"));
        topkLow = Double.parseDouble(value(p, "topkLow", "smx.sf.topkLow", "0.05"));
        gateHigh = Double.parseDouble(value(p, "gateHigh", "smx.sf.gate.high", "0.6"));
        gateLow = Double.parseDouble(value(p, "gateLow", "smx.sf.gate.low", "0.35"));
        gateIncrease = Double.parseDouble(value(p, "gateIncrease", "smx.sf.gate.inc", "1.2"));
        gateDecrease = Double.parseDouble(value(p, "gateDecrease", "smx.sf.gate.dec", "0.9"));
        canonicalDependencyOrder = flag(p, "canonicalDependencyOrder", "smx.sf.canonicalDependencyOrder");
        collisionRule = value(p, "collisionRule", "smx.sf.collisionRule", "FIRST").toUpperCase();
        parallelEdgeRule = value(p, "parallelEdgeRule", "smx.sf.parallelEdgeRule", "DROP").toUpperCase();
        parallelEdges = value(p, "parallelEdges", "smx.sf.parallelEdges", "COLLAPSE").toUpperCase();
        extraEdgesDir = p.get("extraEdgesDir") != null ? p.get("extraEdgesDir") : SchemaGraphBuilder.EXTRA_EDGES_DIR;
        tableMembership = p.get("tableMembership") != null ? p.get("tableMembership").toUpperCase()
                : SchemaGraphBuilder.TABLE_MEMBERSHIP;
        tableMembershipDraw = p.get("tableMembershipDraw") != null ? Integer.parseInt(p.get("tableMembershipDraw").trim())
                : SchemaGraphBuilder.TABLE_MEMBERSHIP_DRAW;
        tableRooted = p.get("tableRooted") != null ? Boolean.parseBoolean(p.get("tableRooted"))
                : SchemaGraphBuilder.TABLE_ROOTED;
        noDatatypeNodes = flag(p, "noDatatypeNodes", "smx.sf.noDatatypeNodes");
        uccNoNotUnique = flag(p, "uccNoNotUnique", "smx.sf.uccNoNotUnique");
        uccUnweighted = flag(p, "uccUnweighted", "smx.sf.uccUnweighted");
        uccTopK = value(p, "uccTopK", "smx.sf.uccTopK", "");
        fdRetention = p.get("fdRetention") != null ? Double.parseDouble(p.get("fdRetention")) : DependencyFilter.RETENTION;
        fdRetentionSeed = p.get("fdRetentionSeed") != null ? Long.parseLong(p.get("fdRetentionSeed").trim())
                : DependencyFilter.RETENTION_SEED;
        fdRewire = p.get("fdRewire") != null ? Boolean.parseBoolean(p.get("fdRewire")) : DependencyFilter.REWIRE;
        fdRewireMode = p.get("fdRewireMode") != null ? p.get("fdRewireMode").toUpperCase()
                : DependencyFilter.REWIRE_MODE.name();
        fdWTarget = p.get("fdWTarget") != null ? Double.parseDouble(p.get("fdWTarget")) : DependencyFilter.W_TARGET;
        classNodes = new ArrayList<>();
        String classes = p.getOrDefault("classNodes", "");
        for (String c : classes.split(",")) {
            String name = c.trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty() || "NONE".equals(name)) continue;
            if (!CLASS_NODES.contains(name)) {
                throw new IllegalArgumentException("classNodes takes a comma-separated subset of " + CLASS_NODES + ", not " + c);
            }
            if (classNodes.contains(name)) throw new IllegalArgumentException("classNodes lists " + name + " twice");
            if (classNodes.stream().anyMatch(n -> n.startsWith(name.split("_")[0]))) {
                throw new IllegalArgumentException("classNodes: one class kind can enter once, either real or shuffled");
            }
            classNodes.add(name);
        }
        withinTable = WithinTableEdges.Spec.of(p.get("withinTableEdges"), p.get("withinTableMeasure"), p.get("withinTableK"),
                p.get("withinTableTieBreak"), p.get("withinTableSeed"), p.get("withinTableLabel"));
        validate();
    }

    static GraphOptions resolve(Map<String, String> parameters) {
        return new GraphOptions(parameters);
    }

    private void validate() {
        if (!List.of("ORIGINAL", "COLLAPSED", "PERMUTED").contains(tableMembership)) {
            throw new IllegalArgumentException("tableMembership must be ORIGINAL, COLLAPSED or PERMUTED");
        }
        if (!List.of("FIRST", "MAX").contains(collisionRule)) {
            throw new IllegalArgumentException("collisionRule must be FIRST or MAX");
        }
        if (!List.of("DROP", "MAX", "AVG", "SUM").contains(parallelEdgeRule)) {
            throw new IllegalArgumentException("parallelEdgeRule must be DROP, MAX, AVG or SUM");
        }
        if (!List.of("COLLAPSE", "SPLIT").contains(parallelEdges)) {
            throw new IllegalArgumentException("parallelEdges must be COLLAPSE or SPLIT");
        }
        if (!List.of("RANDOM", "DEGREE", "EDGE").contains(fdRewireMode)) {
            throw new IllegalArgumentException("fdRewireMode must be RANDOM, DEGREE or EDGE");
        }
    }

    private static String value(Map<String, String> p, String key, String property, String fallback) {
        String v = p.get(key);
        return v != null ? v : System.getProperty(property, fallback);
    }

    /** Boolean.getBoolean semantics for the property; a parameter is read with Boolean.parseBoolean. */
    private static boolean flag(Map<String, String> p, String key, String property) {
        String v = p.get(key);
        return v != null ? Boolean.parseBoolean(v.trim()) : Boolean.getBoolean(property);
    }
}
