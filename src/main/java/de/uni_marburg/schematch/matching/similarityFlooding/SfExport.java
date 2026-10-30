package de.uni_marburg.schematch.matching.similarityFlooding;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Table;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Graph and trajectory export for the Proposition 1 graph-ID check and the X88 mechanism (C's checker; SYNC 2026-10-07).
 *
 * OFF unless -Dsmx.sf.export=<dir> is set; when off every hook returns at once and nothing in the computation changes.
 * Only the default fixpoint path (no restart / non-backtracking) is exported.
 *
 * Per scenario, <dir>/<smx.sf.exportLabel>/<dataset>/<scenario>/:
 *   nodes.tsv   idx, kind_l, kind_r, is_id_l, is_id_r, value_l, value_r, table_l, table_r, dtype_l, dtype_r,
 *               dtype_pair_id, name_id_l, name_id_r, scored, row, col
 *               (scored = both column ID nodes = the pairs the output matrix is read from; row/col = the matrix cell)
 *   edges.tsv   src, dst, channel, coefficient    -- incoming CSR as used: u_dst += coefficient * (sigma0 + x)[src]
 *   init.tsv    idx, sigma0, origin   (name_pair | name_pair@id | name_pair@split | literal_seed:<kind_l>/<kind_r> | zero)
 *   sigma_t.tsv t, idx, value         (t = 0 is sigma0's state x_0 = sigma0; t >= 1 the state after update t)
 *   trace.tsv   t, M_t, residual, converged   (M_t = the divisor applied at update t; residual = the L2 the stop rule uses)
 *   meta.json   settings, update rule, graph sizes, update count
 */
final class SfExport {
    static final String DIR = System.getProperty("smx.sf.export", "");
    static final boolean ENABLED = !DIR.isEmpty();

    private static final ThreadLocal<State> STATE = new ThreadLocal<>();

    private static final class State {
        Path out;
        Map<String, String> settings;
        Set<NodePair> matrixKeys, literalKeys;
        boolean pruned;
        PrintWriter sigma, trace;
    }

    private SfExport() {}

    /** Called in match() once the initial mapping (and the literal seeds) are built. */
    static void begin(String dataset, String scenario, Map<String, String> settings, Set<NodePair> matrixKeys,
                      Set<NodePair> literalKeys, boolean pruned) {
        if (!ENABLED) return;
        State s = new State();
        s.out = Path.of(DIR, System.getProperty("smx.sf.exportLabel", "default"), dataset, scenario);
        s.settings = settings;
        s.matrixKeys = matrixKeys;
        s.literalKeys = literalKeys;
        s.pruned = pruned;
        STATE.set(s);
    }

    private static String esc(Object o) {
        return o == null ? "" : o.toString().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String num(double v) {
        return Double.toString(v);
    }

    private static int columnIndex(Table table, String label) {
        if (table == null) return -1;
        List<Column> cols = table.getColumns();
        for (int i = 0; i < cols.size(); i++) {
            if (cols.get(i).getLabel().equals(label)) return i;
        }
        return -1;
    }

    // Node has no table getter; read the private field (export only, read-only) so Node itself stays unchanged.
    private static final java.lang.reflect.Field TABLE_FIELD;
    static {
        java.lang.reflect.Field f = null;
        if (ENABLED) {
            try {
                f = Node.class.getDeclaredField("table");
                f.setAccessible(true);
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException(e);
            }
        }
        TABLE_FIELD = f;
    }

    private static Table tableOf(Node n) {
        try {
            return (Table) TABLE_FIELD.get(n);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nameId(Node n) {
        if (n.getNodeType() != NodeType.COLUMN) return "";
        Node name = n.isIDNode() ? n.getNameNode() : n;
        return name == null ? "" : name.toString();
    }

    private static String origin(NodePair p, SimilarityFlooding.SeedAttachment at, String literalSeed, State s) {
        Node a = p.getFirstNode(), b = p.getSecondNode();
        boolean colId = a.isIDNode() && b.isIDNode() && a.getNodeType() == NodeType.COLUMN && b.getNodeType() == NodeType.COLUMN
                && a.getNameNode() != null && b.getNameNode() != null;
        if (at == SimilarityFlooding.SeedAttachment.NAME) {
            if (s.matrixKeys.contains(p)) return "name_pair";
            if (s.literalKeys.contains(p)) return "literal_seed:" + a.getNodeType() + "/" + b.getNodeType();
            return "zero";
        }
        if ("PREFIX_SUFFIX".equals(literalSeed) && !a.isIDNode() && !b.isIDNode()
                && !(isColumnName(a) && isColumnName(b)) && s.literalKeys.contains(p)) {
            return "literal_seed:" + a.getNodeType() + "/" + b.getNodeType();
        }
        if (colId) return at == SimilarityFlooding.SeedAttachment.ID ? "name_pair@id" : "name_pair@split";
        if (s.matrixKeys.contains(p)) return at == SimilarityFlooding.SeedAttachment.ID ? "zero" : "name_pair@split";
        return "zero";
    }

    private static boolean isColumnName(Node n) {
        return !n.isIDNode() && n.getNodeType().equals(NodeType.COLUMN) && n.getRepresentedColumn() != null;
    }

    /** Called in similarityFlooding() once sigma_0 and the CSR adjacency exist, before the first update. */
    static void graph(List<NodePair> nodes, double[] sigma0, int[] rowPtr, int[] src, double[] coeff, int[] channelOf,
                      List<String> channelNames, SimilarityFlooding.SeedAttachment at, String literalSeed) {
        State s = STATE.get();
        if (!ENABLED || s == null) return;
        try {
            Files.createDirectories(s.out);
            try (PrintWriter w = writer(s.out.resolve("nodes.tsv"))) {
                w.println("idx\tkind_l\tkind_r\tis_id_l\tis_id_r\tvalue_l\tvalue_r\ttable_l\ttable_r\tdtype_l\tdtype_r\t"
                        + "dtype_pair_id\tname_id_l\tname_id_r\tscored\trow\tcol");
                for (int i = 0; i < nodes.size(); i++) {
                    Node a = nodes.get(i).getFirstNode(), b = nodes.get(i).getSecondNode();
                    boolean scored = a.isIDNode() && b.isIDNode() && a.getNodeType() == NodeType.COLUMN
                            && b.getNodeType() == NodeType.COLUMN;
                    int row = -1, col = -1;
                    if (scored && a.getNameNode() != null && b.getNameNode() != null && tableOf(a) != null && tableOf(b) != null) {
                        int ia = columnIndex(tableOf(a), a.getNameNode().getValue());
                        int ib = columnIndex(tableOf(b), b.getNameNode().getValue());
                        if (ia >= 0 && ib >= 0) {
                            row = tableOf(a).getOffset() + ia;
                            col = tableOf(b).getOffset() + ib;
                        }
                    }
                    String dl = a.getDatatype() == null ? "" : a.getDatatype().toString();
                    String dr = b.getDatatype() == null ? "" : b.getDatatype().toString();
                    w.println(i + "\t" + a.getNodeType() + "\t" + b.getNodeType() + "\t" + a.isIDNode() + "\t" + b.isIDNode()
                            + "\t" + esc(a.getValue()) + "\t" + esc(b.getValue())
                            + "\t" + esc(tableOf(a) == null ? null : tableOf(a).getName())
                            + "\t" + esc(tableOf(b) == null ? null : tableOf(b).getName())
                            + "\t" + esc(dl) + "\t" + esc(dr) + "\t" + (dl.isEmpty() && dr.isEmpty() ? "" : esc(dl + "|" + dr))
                            + "\t" + esc(nameId(a)) + "\t" + esc(nameId(b)) + "\t" + scored + "\t" + row + "\t" + col);
                }
            }
            try (PrintWriter w = writer(s.out.resolve("edges.tsv"))) {
                w.println("src\tdst\tchannel\tcoefficient");
                for (int dst = 0; dst < nodes.size(); dst++) {
                    for (int k = rowPtr[dst]; k < rowPtr[dst + 1]; k++) {
                        w.println(src[k] + "\t" + dst + "\t" + esc(channelNames.get(channelOf[k])) + "\t" + num(coeff[k]));
                    }
                }
            }
            try (PrintWriter w = writer(s.out.resolve("init.tsv"))) {
                w.println("idx\tsigma0\torigin");
                for (int i = 0; i < nodes.size(); i++) {
                    w.println(i + "\t" + num(sigma0[i]) + "\t" + origin(nodes.get(i), at, literalSeed, s));
                }
            }
            s.settings.put("nodes", Integer.toString(nodes.size()));
            s.settings.put("edges", Integer.toString(coeff.length));
            s.sigma = writer(s.out.resolve("sigma_t.tsv"));
            s.sigma.println("t\tidx\tvalue");
            for (int i = 0; i < sigma0.length; i++) s.sigma.println("0\t" + i + "\t" + num(sigma0[i]));
            s.trace = writer(s.out.resolve("trace.tsv"));
            s.trace.println("t\tM_t\tresidual\tconverged");
        } catch (IOException e) {
            throw new IllegalStateException("smx.sf.export: cannot write " + s.out, e);
        }
    }

    /** After update t (1-based): the applied divisor, the stop rule's residual and the new state. */
    static void step(int t, double divisor, double residual, boolean converged, double[] state) {
        State s = STATE.get();
        if (!ENABLED || s == null || s.sigma == null) return;
        s.trace.println(t + "\t" + num(divisor) + "\t" + num(residual) + "\t" + converged);
        for (int i = 0; i < state.length; i++) s.sigma.println(t + "\t" + i + "\t" + num(state[i]));
    }

    /** After the loop. */
    static void end(int updates, boolean converged) {
        State s = STATE.get();
        if (!ENABLED || s == null) return;
        try {
            if (s.sigma != null) s.sigma.close();
            if (s.trace != null) s.trace.close();
            s.settings.put("updates", Integer.toString(updates));
            s.settings.put("converged", Boolean.toString(converged));
            StringBuilder j = new StringBuilder("{\n");
            int n = 0;
            for (Map.Entry<String, String> e : s.settings.entrySet()) {
                j.append(n++ == 0 ? "" : ",\n").append("  \"").append(e.getKey().replace("\"", "'")).append("\": \"")
                        .append(e.getValue() == null ? "" : e.getValue().replace("\\", "/").replace("\"", "'")).append('"');
            }
            j.append("\n}\n");
            Files.createDirectories(s.out);
            Files.writeString(s.out.resolve("meta.json"), j.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("smx.sf.export: cannot write meta.json in " + s.out, e);
        } finally {
            STATE.remove();
        }
    }

    private static PrintWriter writer(Path p) throws IOException {
        return new PrintWriter(Files.newBufferedWriter(p, StandardCharsets.UTF_8));
    }

    static String fmt(String f, Object... a) {
        return String.format(Locale.ROOT, f, a);
    }
}
