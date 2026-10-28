package de.uni_marburg.schematch.similarity.lexical;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A minimal reader for the WordNet 3.0 noun database (Miller, CACM 1995) in the format NLTK
 * installs ({@code nltk_data/corpora/wordnet.zip} or the unpacked {@code wordnet/} directory), with
 * the standard taxonomy-based word similarities.
 *
 * <p>Only nouns are loaded: column labels are overwhelmingly nominal. Hypernym edges are {@code @}
 * and instance hypernyms {@code @i}, as in NLTK.
 *
 * <p>Measures (all in [0, 1]):
 * <ul>
 *     <li>PATH: {@code 1 / (d + 1)}, d the shortest hypernym path (NLTK path_similarity).</li>
 *     <li>WUP: Wu &amp; Palmer (ACL 1994) as NLTK computes it, with the least common subsumer chosen
 *     by maximal minimum depth; ties are broken by smallest synset offset.</li>
 *     <li>LCH: Leacock &amp; Chodorow (1998), {@code -log((d+1)/(2D))} with D = 19 for nouns,
 *     divided by its maximum {@code log(2D)}.</li>
 *     <li>RES, LIN, JCN: Resnik (IJCAI 1995), Lin (ICML 1998) and Jiang &amp; Conrath (1997) with
 *     <em>intrinsic</em> information content (Seco, Veale &amp; Hayes, ECAI 2004),
 *     {@code IC(c) = 1 - log(hypo(c) + 1) / log(N)}, because no sense-tagged corpus IC file is
 *     installed. JCN's distance {@code IC1 + IC2 - 2 IC(lcs)} lies in [0, 2] and is mapped to
 *     {@code 1 - dist / 2}.</li>
 * </ul>
 */
public final class WordNet {
    public static final String PATH = "PATH";
    public static final String WUP = "WUP";
    public static final String LCH = "LCH";
    public static final String RES = "RES";
    public static final String LIN = "LIN";
    public static final String JCN = "JCN";

    private static final int NOUN_TAXONOMY_DEPTH = 19;
    private static final Map<String, WordNet> INSTANCES = new ConcurrentHashMap<>();
    private static final String[][] NOUN_SUBSTITUTIONS = {
            {"s", ""}, {"ses", "s"}, {"ves", "f"}, {"xes", "x"}, {"zes", "z"},
            {"ches", "ch"}, {"shes", "sh"}, {"men", "man"}, {"ies", "y"}};

    private final Map<String, int[]> index = new HashMap<>();
    private final Map<String, List<String>> exceptions = new HashMap<>();
    private final Map<Integer, int[]> hypernyms = new HashMap<>();
    private final Map<Integer, int[]> hyponyms = new HashMap<>();
    private final Map<Integer, Map<Integer, Integer>> ancestorCache = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> minDepth = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> maxDepth = new ConcurrentHashMap<>();
    private final Map<Integer, Double> icCache = new ConcurrentHashMap<>();

    private WordNet() {
    }

    /** The default location NLTK uses on this machine: {@code ~/nltk_data/corpora/wordnet.zip}. */
    public static String defaultPath() {
        return System.getProperty("user.home") + File.separator + "nltk_data" + File.separator
                + "corpora" + File.separator + "wordnet.zip";
    }

    public static WordNet load(String path) {
        return load(path, "");
    }

    /**
     * @param icFile an NLTK information-content file ({@code ic-brown.dat}, {@code ic-semcor.dat}, ...
     *               from {@code nltk_data/corpora/wordnet_ic}); empty means intrinsic IC. Corpus IC
     *               {@code -log(count(c) / count(root))} is divided by its maximum
     *               {@code log(count(root))} so that RES, LIN and JCN use the same [0, 1] formulas
     *               in both modes (LIN is unaffected by the scaling).
     */
    public static WordNet load(String path, String icFile) {
        String p = (path == null || path.isBlank()) ? defaultPath() : path;
        String ic = icFile == null ? "" : icFile;
        return INSTANCES.computeIfAbsent(p + "|" + ic, key -> {
            WordNet wn = read(p);
            if (!ic.isBlank()) {
                wn.readIc(ic);
            }
            return wn;
        });
    }

    private Map<Integer, Double> corpusCounts;
    private double corpusRoot;

    private void readIc(String icFile) {
        Map<Integer, Double> counts = new HashMap<>();
        double root = 0;
        try (BufferedReader r = reader(new FileInputStream(icFile))) {
            String line = r.readLine(); // "wnver::<hash>"
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 2 || !f[0].endsWith("n")) {
                    continue; // nouns only, like the rest of this reader
                }
                int offset = Integer.parseInt(f[0].substring(0, f[0].length() - 1));
                double c = Double.parseDouble(f[1]);
                counts.put(offset, c);
                if (f.length > 2 && f[2].equals("ROOT")) {
                    root += c;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not read IC file " + icFile, e);
        }
        if (root <= 0) {
            throw new IllegalArgumentException("IC file " + icFile + " has no noun ROOT counts");
        }
        this.corpusCounts = counts;
        this.corpusRoot = root;
    }

    public boolean usesCorpusIc() {
        return corpusCounts != null;
    }

    private static WordNet read(String path) {
        WordNet wn = new WordNet();
        try {
            File f = new File(path);
            if (f.isFile() && path.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                try (ZipFile zip = new ZipFile(f)) {
                    wn.parseIndex(open(zip, "index.noun"));
                    wn.parseData(open(zip, "data.noun"));
                    InputStream exc = openOptional(zip, "noun.exc");
                    if (exc != null) {
                        wn.parseExceptions(exc);
                    }
                }
            } else if (f.isDirectory()) {
                wn.parseIndex(new FileInputStream(new File(f, "index.noun")));
                wn.parseData(new FileInputStream(new File(f, "data.noun")));
                File exc = new File(f, "noun.exc");
                if (exc.isFile()) {
                    wn.parseExceptions(new FileInputStream(exc));
                }
            } else {
                throw new IllegalArgumentException("WordNet not found at " + path
                        + " (expected NLTK's wordnet.zip or a directory with index.noun/data.noun)");
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not read WordNet from " + path, e);
        }
        return wn;
    }

    private static InputStream open(ZipFile zip, String name) throws IOException {
        InputStream in = openOptional(zip, name);
        if (in == null) {
            throw new IOException(name + " not found in " + zip.getName());
        }
        return in;
    }

    private static InputStream openOptional(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry("wordnet/" + name);
        if (entry == null) {
            entry = zip.getEntry(name);
        }
        return entry == null ? null : zip.getInputStream(entry);
    }

    private static BufferedReader reader(InputStream in) {
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private void parseIndex(InputStream in) throws IOException {
        try (BufferedReader r = reader(in)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith(" ")) {
                    continue;
                }
                String[] f = line.trim().split(" ");
                int synsetCount = Integer.parseInt(f[2]);
                int pointerCount = Integer.parseInt(f[3]);
                int start = 4 + pointerCount + 2;
                int[] offsets = new int[synsetCount];
                for (int k = 0; k < synsetCount; k++) {
                    offsets[k] = Integer.parseInt(f[start + k]);
                }
                index.put(f[0], offsets);
            }
        }
    }

    private void parseData(InputStream in) throws IOException {
        Map<Integer, List<Integer>> up = new HashMap<>();
        Map<Integer, List<Integer>> down = new HashMap<>();
        try (BufferedReader r = reader(in)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith(" ")) {
                    continue;
                }
                int bar = line.indexOf(" | ");
                String[] f = (bar >= 0 ? line.substring(0, bar) : line).trim().split(" ");
                int offset = Integer.parseInt(f[0]);
                int wordCount = Integer.parseInt(f[3], 16);
                int p = 4 + 2 * wordCount;
                int pointerCount = Integer.parseInt(f[p]);
                p++;
                List<Integer> hyper = up.computeIfAbsent(offset, k -> new ArrayList<>());
                List<Integer> hypo = down.computeIfAbsent(offset, k -> new ArrayList<>());
                for (int k = 0; k < pointerCount; k++) {
                    String symbol = f[p];
                    int target = Integer.parseInt(f[p + 1]);
                    String pos = f[p + 2];
                    p += 4;
                    if (!pos.equals("n")) {
                        continue;
                    }
                    if (symbol.equals("@") || symbol.equals("@i")) {
                        hyper.add(target);
                    } else if (symbol.equals("~") || symbol.equals("~i")) {
                        hypo.add(target);
                    }
                }
            }
        }
        up.forEach((k, v) -> hypernyms.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
        down.forEach((k, v) -> hyponyms.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
    }

    private void parseExceptions(InputStream in) throws IOException {
        try (BufferedReader r = reader(in)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split(" ");
                if (f.length >= 2) {
                    exceptions.put(f[0], new ArrayList<>(Arrays.asList(f).subList(1, f.length)));
                }
            }
        }
    }

    public int numSynsets() {
        return hypernyms.size();
    }

    public boolean contains(String lemma) {
        return !synsets(lemma).isEmpty();
    }

    /**
     * Noun synsets of a word after NLTK-style morphological reduction (morphy): an exception-list
     * entry replaces the suffix rules; otherwise the surface form and every single-rule reduction
     * that exists in the index contribute their senses.
     */
    public List<Integer> synsets(String word) {
        String w = word.toLowerCase(Locale.ROOT).replace(' ', '_');
        Set<String> forms = new LinkedHashSet<>();
        forms.add(w);
        if (exceptions.containsKey(w)) {
            forms.addAll(exceptions.get(w));
        } else {
            for (String[] rule : NOUN_SUBSTITUTIONS) {
                if (w.endsWith(rule[0]) && w.length() > rule[0].length()) {
                    forms.add(w.substring(0, w.length() - rule[0].length()) + rule[1]);
                }
            }
        }
        List<Integer> out = new ArrayList<>();
        for (String form : forms) {
            int[] offsets = index.get(form);
            if (offsets != null) {
                for (int o : offsets) {
                    if (!out.contains(o)) {
                        out.add(o);
                    }
                }
            }
        }
        return out;
    }

    /** Every hypernym ancestor (inclusive) with its shortest distance. */
    Map<Integer, Integer> ancestors(int synset) {
        return ancestorCache.computeIfAbsent(synset, s -> {
            Map<Integer, Integer> dist = new HashMap<>();
            Deque<Integer> queue = new ArrayDeque<>();
            dist.put(s, 0);
            queue.add(s);
            while (!queue.isEmpty()) {
                int c = queue.poll();
                for (int h : hypernyms.getOrDefault(c, new int[0])) {
                    if (!dist.containsKey(h)) {
                        dist.put(h, dist.get(c) + 1);
                        queue.add(h);
                    }
                }
            }
            return dist;
        });
    }

    int minDepth(int synset) {
        Integer cached = minDepth.get(synset);
        if (cached != null) {
            return cached;
        }
        int[] up = hypernyms.getOrDefault(synset, new int[0]);
        int d = 0;
        if (up.length > 0) {
            d = Integer.MAX_VALUE;
            for (int h : up) {
                d = Math.min(d, minDepth(h) + 1);
            }
        }
        minDepth.put(synset, d);
        return d;
    }

    int maxDepth(int synset) {
        Integer cached = maxDepth.get(synset);
        if (cached != null) {
            return cached;
        }
        int d = 0;
        for (int h : hypernyms.getOrDefault(synset, new int[0])) {
            d = Math.max(d, maxDepth(h) + 1);
        }
        maxDepth.put(synset, d);
        return d;
    }

    /** Information content in [0, 1]: corpus IC when an IC file is loaded, else intrinsic (Seco et al. 2004). */
    double ic(int synset) {
        if (corpusCounts != null) {
            double c = corpusCounts.getOrDefault(synset, 0.0);
            // an unseen concept is maximally informative, as NLTK's infinite IC, capped at 1
            return c <= 0 ? 1.0 : Math.min(1.0, -Math.log(c / corpusRoot) / Math.log(corpusRoot));
        }
        return icCache.computeIfAbsent(synset, s -> {
            Set<Integer> seen = new HashSet<>();
            Deque<Integer> stack = new ArrayDeque<>();
            stack.push(s);
            while (!stack.isEmpty()) {
                int c = stack.pop();
                for (int h : hyponyms.getOrDefault(c, new int[0])) {
                    if (seen.add(h)) {
                        stack.push(h);
                    }
                }
            }
            return 1.0 - Math.log(seen.size() + 1.0) / Math.log(numSynsets());
        });
    }

    /** Similarity of two synsets under {@code measure}; 0 when they share no ancestor. */
    public double synsetSimilarity(int a, int b, String measure) {
        if (a == b && !measure.equals(RES)) {
            return 1.0;
        }
        Map<Integer, Integer> ancA = ancestors(a);
        Map<Integer, Integer> ancB = ancestors(b);
        int shortest = Integer.MAX_VALUE;
        Integer wupLcs = null;
        double bestIc = -1;
        for (Map.Entry<Integer, Integer> e : ancA.entrySet()) {
            Integer db = ancB.get(e.getKey());
            if (db == null) {
                continue;
            }
            int c = e.getKey();
            shortest = Math.min(shortest, e.getValue() + db);
            if (wupLcs == null || minDepth(c) > minDepth(wupLcs)
                    || (minDepth(c) == minDepth(wupLcs) && c < wupLcs)) {
                wupLcs = c;
            }
            if (measure.equals(RES) || measure.equals(LIN) || measure.equals(JCN)) {
                bestIc = Math.max(bestIc, ic(c));
            }
        }
        if (wupLcs == null) {
            return 0.0;
        }
        switch (measure) {
            case PATH:
                return 1.0 / (shortest + 1.0);
            case WUP: {
                int depth = maxDepth(wupLcs) + 1;
                double len1 = ancA.get(wupLcs) + depth;
                double len2 = ancB.get(wupLcs) + depth;
                return 2.0 * depth / (len1 + len2);
            }
            case LCH: {
                double max = Math.log(2.0 * NOUN_TAXONOMY_DEPTH);
                double v = -Math.log((shortest + 1.0) / (2.0 * NOUN_TAXONOMY_DEPTH));
                return Math.max(0.0, Math.min(1.0, v / max));
            }
            case RES:
                return bestIc;
            case LIN: {
                double denom = ic(a) + ic(b);
                return denom > 0 ? Math.min(1.0, 2.0 * bestIc / denom) : 0.0;
            }
            case JCN: {
                double dist = ic(a) + ic(b) - 2.0 * bestIc;
                return Math.max(0.0, Math.min(1.0, 1.0 - dist / 2.0));
            }
            default:
                throw new IllegalArgumentException("Unknown WordNet measure: " + measure);
        }
    }

    /** Best synset-pair similarity of two words; -1 when either is not a WordNet noun. */
    public double wordSimilarity(String w1, String w2, String measure) {
        List<Integer> a = synsets(w1);
        List<Integer> b = synsets(w2);
        if (a.isEmpty() || b.isEmpty()) {
            return -1.0;
        }
        double best = 0.0;
        for (int x : a) {
            for (int y : b) {
                best = Math.max(best, synsetSimilarity(x, y, measure));
            }
        }
        return best;
    }

    /**
     * Groups adjacent tokens into WordNet compounds, longest first ("zip", "code" becomes
     * "zip_code"), so that multi-word lemmas are looked up as one concept.
     */
    public List<String> compounds(List<String> tokens, int maxLength) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            int taken = 1;
            for (int len = Math.min(maxLength, tokens.size() - i); len >= 2; len--) {
                String joined = String.join("_", tokens.subList(i, i + len));
                if (index.containsKey(joined)) {
                    out.add(joined);
                    taken = len;
                    break;
                }
            }
            if (taken == 1) {
                out.add(tokens.get(i));
            }
            i += taken;
        }
        return out;
    }
}
