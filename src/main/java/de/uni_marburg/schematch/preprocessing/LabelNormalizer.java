package de.uni_marburg.schematch.preprocessing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a column label into word tokens for the token-based label matchers.
 *
 * <p>The modes are cumulative:
 * <ul>
 *     <li>{@code RAW}: the label itself, as a single token.</li>
 *     <li>{@code SPLIT}: camelCase / snake_case / digit boundaries, lower-cased. The split is the
 *     regular expression of Valentine's Similarity Flooding string matcher, so "XMLParser" gives
 *     [xml, parser] and "order123" gives [order, 123].</li>
 *     <li>{@code EXPAND}: SPLIT, then abbreviation expansion from {@link #ABBREVIATIONS}.</li>
 *     <li>{@code STEM}: EXPAND, then Harman's S-stemmer (plural removal only).</li>
 *     <li>{@code PORTER}: EXPAND, then the Porter stemmer ({@link PorterStemmer}).</li>
 * </ul>
 *
 * <p>The abbreviation list is this project's own short list of generic English database
 * abbreviations, not a published resource; it can be extended but is deliberately small so that
 * it does not encode knowledge of any benchmark's labels.
 */
public final class LabelNormalizer {
    public static final String RAW = "RAW";
    public static final String SPLIT = "SPLIT";
    public static final String EXPAND = "EXPAND";
    public static final String STEM = "STEM";
    public static final String PORTER = "PORTER";

    private static final Pattern TOKEN = Pattern.compile("[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|\\d+");

    public static final Map<String, String> ABBREVIATIONS = Map.ofEntries(
            Map.entry("id", "identifier"), Map.entry("no", "number"), Map.entry("nr", "number"),
            Map.entry("num", "number"), Map.entry("qty", "quantity"), Map.entry("amt", "amount"),
            Map.entry("addr", "address"), Map.entry("desc", "description"), Map.entry("descr", "description"),
            Map.entry("dt", "date"), Map.entry("tel", "telephone"), Map.entry("ph", "phone"),
            Map.entry("cust", "customer"), Map.entry("emp", "employee"), Map.entry("dept", "department"),
            Map.entry("org", "organization"), Map.entry("prod", "product"), Map.entry("cat", "category"),
            Map.entry("info", "information"), Map.entry("max", "maximum"), Map.entry("min", "minimum"),
            Map.entry("avg", "average"), Map.entry("cnt", "count"), Map.entry("pct", "percent"),
            Map.entry("yr", "year"), Map.entry("mo", "month"), Map.entry("nm", "name"),
            Map.entry("fname", "first name"), Map.entry("lname", "last name"), Map.entry("dob", "date of birth"),
            Map.entry("tmp", "temporary"), Map.entry("temp", "temperature"), Map.entry("src", "source"),
            Map.entry("tgt", "target"), Map.entry("ref", "reference"), Map.entry("seq", "sequence"),
            Map.entry("idx", "index"), Map.entry("val", "value"), Map.entry("std", "standard"),
            Map.entry("loc", "location"), Map.entry("lat", "latitude"), Map.entry("lon", "longitude"),
            Map.entry("lng", "longitude"), Map.entry("mgr", "manager"), Map.entry("acct", "account"),
            Map.entry("st", "street"), Map.entry("zip", "postcode"), Map.entry("ctry", "country"),
            Map.entry("prev", "previous"), Map.entry("curr", "current"), Map.entry("conf", "confidence"),
            Map.entry("pos", "position"), Map.entry("wt", "weight"), Map.entry("ht", "height")
    );

    private LabelNormalizer() {
    }

    /** Valentine's camelCase split, case preserved; the label itself when nothing matches. */
    public static List<String> split(String label) {
        List<String> tokens = new ArrayList<>();
        if (label == null || label.isEmpty()) {
            return tokens;
        }
        Matcher m = TOKEN.matcher(label);
        while (m.find()) {
            tokens.add(m.group());
        }
        if (tokens.isEmpty()) {
            tokens.add(label);
        }
        return tokens;
    }

    public static List<String> tokens(String label, String mode) {
        String m = mode == null ? SPLIT : mode.toUpperCase(Locale.ROOT);
        if (m.equals(RAW)) {
            List<String> raw = new ArrayList<>();
            if (label != null && !label.isEmpty()) {
                raw.add(label);
            }
            return raw;
        }
        if (!m.equals(SPLIT) && !m.equals(EXPAND) && !m.equals(STEM) && !m.equals(PORTER)) {
            throw new IllegalArgumentException("Unknown label normalization: " + mode);
        }
        List<String> out = new ArrayList<>();
        for (String token : split(label)) {
            String t = token.toLowerCase(Locale.ROOT);
            if (!m.equals(SPLIT)) {
                String expanded = ABBREVIATIONS.get(t);
                if (expanded != null) {
                    for (String e : expanded.split(" ")) {
                        out.add(stem(e, m));
                    }
                    continue;
                }
            }
            out.add(stem(t, m));
        }
        return out;
    }

    private static String stem(String token, String mode) {
        return switch (mode) {
            case STEM -> sStem(token);
            case PORTER -> PorterStemmer.stem(token);
            default -> token;
        };
    }

    /**
     * Harman's S-stemmer (JASIS 1991): only the first applicable rule fires.
     * ies -> y (not eies, aies); es -> e (not aes, ees, oes); s -> "" (not us, ss).
     */
    public static String sStem(String w) {
        if (w.length() > 3 && w.endsWith("ies") && !w.endsWith("eies") && !w.endsWith("aies")) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.length() > 2 && w.endsWith("es") && !w.endsWith("aes") && !w.endsWith("ees") && !w.endsWith("oes")) {
            return w.substring(0, w.length() - 1);
        }
        if (w.length() > 1 && w.endsWith("s") && !w.endsWith("us") && !w.endsWith("ss")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }
}
