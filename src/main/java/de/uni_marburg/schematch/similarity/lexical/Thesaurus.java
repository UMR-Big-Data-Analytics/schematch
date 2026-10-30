package de.uni_marburg.schematch.similarity.lexical;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A synonym resource: terms mapped to concept identifiers. Two terms are synonyms when they share
 * a concept. This is the "auxiliary thesaurus / synonym table" of Cupid and COMA, and the access
 * path for controlled vocabularies such as UMLS or OMOP.
 *
 * <p>Formats ({@code format}):
 * <ul>
 *     <li>SYNSETS: one synonym set per line, terms separated by commas (or tabs); '#' starts a
 *     comment line. Each line is one concept.</li>
 *     <li>MRCONSO: UMLS {@code MRCONSO.RRF}, pipe-separated; field 1 is the CUI and field 15 the
 *     term string. UMLS is licensed and is not shipped; point {@code path} at a local copy.</li>
 *     <li>OMOP: an OMOP {@code CONCEPT_SYNONYM.csv} (tab-separated, concept_id then
 *     concept_synonym_name) or {@code CONCEPT.csv} (concept_id, concept_name, ...).</li>
 * </ul>
 * Terms are lower-cased and their internal whitespace / underscores collapsed to single spaces, so
 * "Zip_Code" and "zip code" are the same term.
 */
public final class Thesaurus {
    private static final Map<String, Thesaurus> INSTANCES = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> concepts = new HashMap<>();

    private Thesaurus() {
    }

    public static String normalise(String term) {
        return term.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').trim().replaceAll("\\s+", " ");
    }

    public static Thesaurus load(String path, String format) {
        return INSTANCES.computeIfAbsent(path + "|" + format, key -> read(Path.of(path), format));
    }

    private static Thesaurus read(Path path, String format) {
        Thesaurus t = new Thesaurus();
        String f = format.toUpperCase(Locale.ROOT);
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int n = 0;
            boolean header = true;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                switch (f) {
                    case "SYNSETS" -> {
                        String id = "L" + (n++);
                        for (String term : line.split("[,\t]")) {
                            t.add(term, id);
                        }
                    }
                    case "MRCONSO" -> {
                        String[] cells = line.split("\\|", -1);
                        if (cells.length > 14) {
                            t.add(cells[14], cells[0]);
                        }
                    }
                    case "OMOP" -> {
                        String[] cells = line.split("\t", -1);
                        if (header) {
                            header = false;
                            if (!cells[0].chars().allMatch(Character::isDigit)) {
                                continue;
                            }
                        }
                        if (cells.length >= 2) {
                            t.add(cells[1], "OMOP" + cells[0]);
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown thesaurus format: " + format);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read thesaurus " + path, e);
        }
        return t;
    }

    private void add(String term, String concept) {
        String key = normalise(term);
        if (!key.isEmpty()) {
            concepts.computeIfAbsent(key, k -> new HashSet<>()).add(concept);
        }
    }

    public boolean contains(String term) {
        return concepts.containsKey(normalise(term));
    }

    public Set<String> concepts(String term) {
        return concepts.getOrDefault(normalise(term), Set.of());
    }

    public boolean synonyms(String a, String b) {
        Set<String> x = concepts(a);
        if (x.isEmpty()) {
            return false;
        }
        for (String c : concepts(b)) {
            if (x.contains(c)) {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return concepts.size();
    }
}
