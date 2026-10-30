package de.uni_marburg.schematch.similarity.lexical;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Corpus information content read from an NLTK-format IC file (a synthetic one here). */
class WordNetIcTest {

    @Test
    void corpusIc(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(new File(WordNet.defaultPath()).isFile(), "NLTK WordNet not installed");
        WordNet plain = WordNet.load("");
        // counts that shrink with depth below every ancestor of dog and cat, as a corpus IC file's
        // propagated counts do; the root carries the total
        Set<Integer> synsets = new HashSet<>();
        for (String w : List.of("dog", "cat", "invoice")) {
            for (int s : plain.synsets(w)) {
                synsets.addAll(plain.ancestors(s).keySet());
            }
        }
        StringBuilder ic = new StringBuilder("wnver::synthetic\n");
        for (int s : synsets) {
            // max depth grows along every hypernym edge, so these counts shrink monotonically
            // downwards as propagated corpus counts do; 2^(30 - depth) >= 2^10 keeps IC < 1
            int depth = plain.maxDepth(s);
            ic.append(s).append("n ").append(Math.pow(2, 30 - depth));
            if (depth == 0) {
                ic.append(" ROOT");
            }
            ic.append('\n');
        }
        Path file = dir.resolve("ic-synthetic.dat");
        Files.writeString(file, ic.toString());
        WordNet corpus = WordNet.load("", file.toString());
        assertTrue(corpus.usesCorpusIc());
        double same = corpus.wordSimilarity("dog", "dog", WordNet.LIN);
        double near = corpus.wordSimilarity("dog", "cat", WordNet.LIN);
        double far = corpus.wordSimilarity("dog", "invoice", WordNet.LIN);
        assertEquals(1.0, same, 1e-9);
        assertTrue(near > far && near < 1.0, near + " " + far);
        Map<Integer, Integer> anc = plain.ancestors(plain.synsets("dog").get(0));
        assertTrue(anc.size() > 5);
    }
}
