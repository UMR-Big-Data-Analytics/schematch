package de.uni_marburg.schematch.matching.similarityFlooding;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Database;
import de.uni_marburg.schematch.data.Table;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * One class per column, for SF's class nodes and for the additive class term (ClassTermMatcher), so that both
 * sides of the information-matched comparison read identical classes.
 *
 * <ul>
 *   <li>{@link Kind#SHAPE}: the dominant value shape (upper letter A, lower letter a, digit 9, whitespace ' ', any
 *       other character kept; runs collapsed; at most 32 characters) if at least half of the non-empty values have
 *       it, else {@code MIXED} (sf-matcher-study layer3b/f7.py column_classes).</li>
 *   <li>{@link Kind#SEMTYPE}: a rule-based semantic type, the first of 19 regex and dictionary rules that a stripped
 *       value meets (else {@code other}), and the column's majority type if it covers at least 60% of its non-blank
 *       values, else {@code mixed}; written with a trailing underscore ("country_") so that a class literal cannot
 *       equal a column name (layer3b/f13.py column_types).</li>
 * </ul>
 * Python's string predicates are reproduced per code point (str.isupper/islower/isdigit/isspace, Unicode \d and
 * \s), so the classes equal the study's edge files.
 */
public final class ColumnClasses {
    private ColumnClasses() {}

    public enum Kind {
        SHAPE("pattern", "shape"), SEMTYPE("semtype", "semtype");

        /** Edge label of the class nodes, and the family name in the shuffle seed. */
        final String label;
        final String family;

        Kind(String label, String family) {
            this.label = label;
            this.family = family;
        }
    }

    public static final String MIXED = "MIXED";

    /** One class per column of the database, in matrix order. */
    public static String[] classes(Database database, Kind kind) {
        List<Column> columns = database.getColumns();
        String[] out = new String[columns.size()];
        for (int c = 0; c < out.length; c++) out[c] = columnClass(columns.get(c).getValues(), kind);
        return out;
    }

    public static String columnClass(List<String> values, Kind kind) {
        return kind == Kind.SHAPE ? shapeClass(values) : semanticTypeClass(values);
    }

    // ------------------------------------------------------------------------------------------- value shapes

    public static String shapeClass(List<String> values) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (String v : values) {
            if (v.isEmpty()) continue;
            counts.merge(shape(v), 1, Integer::sum);
            total++;
        }
        String top = "";
        int n = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) if (e.getValue() > n) { top = e.getKey(); n = e.getValue(); }
        // Ties go to the shape seen first (Counter.most_common); an all-whitespace shape is no class.
        return total > 0 && 2L * n >= total && !top.chars().allMatch(ch -> ch == ' ') ? top : MIXED;
    }

    /**
     * Upper letter A, lower letter a, digit 9, whitespace ' ', any other character kept; runs collapsed;
     * at most 32 characters. Per code point with Python's str predicates, unlike PatternProfileMatcher.shape,
     * which works on UTF-16 units and does not count no-break spaces as whitespace.
     */
    public static String shape(String v) {
        StringBuilder out = new StringBuilder();
        int length = 0, last = -1;
        for (int k = 0; k < v.length(); ) {
            int c = v.codePointAt(k);
            k += Character.charCount(c);
            int m = Character.isUpperCase(c) ? 'A' : Character.isLowerCase(c) ? 'a' : isDigit(c) ? '9'
                    : isSpace(c) ? ' ' : c;
            if (length > 0 && m == last) continue;
            out.appendCodePoint(m);
            last = m;
            if (++length >= 32) break;
        }
        return out.toString();
    }

    /** str.isdigit: decimal digits plus digit-valued other numbers such as superscripts. */
    static boolean isDigit(int c) {
        if (Character.isDigit(c)) return true;
        if (Character.getType(c) != Character.OTHER_NUMBER) return false;
        int value = Character.getNumericValue(c);
        return value >= 0 && value <= 9;
    }

    /** str.isspace: Java whitespace plus the no-break spaces (Zs) and NEL. */
    static boolean isSpace(int c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85;
    }

    // ------------------------------------------------------------------------------------------ semantic types

    /** Share of a column's non-blank values its majority type needs (f13.py SHARE). */
    static final double SEMTYPE_SHARE = 0.6;

    public static String semanticTypeClass(List<String> values) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (String v : values) {
            if (strip(v).isEmpty()) continue;
            counts.merge(semanticType(v), 1, Integer::sum);
            total++;
        }
        String top = "";
        int n = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) if (e.getValue() > n) { top = e.getKey(); n = e.getValue(); }
        return total > 0 && (double) n / total >= SEMTYPE_SHARE ? top + "_" : "mixed_";
    }

    /** The first rule a stripped value meets, else "other" (f13.py value_type). */
    public static String semanticType(String raw) {
        String v = strip(raw);
        for (Rule rule : RULES) if (rule.test.test(v)) return rule.name;
        return "other";
    }

    private record Rule(String name, Predicate<String> test) {}

    // Python 3 str patterns: \d is any Unicode decimal digit, \s any character with str.isspace(), which is Java's
    // Unicode White_Space plus the separators U+001C..U+001F (written out as SP below).
    private static final String SP = "\\s\\x1c-\\x1f";
    private static final String NUM = "[-+]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?";

    private static Pattern re(String regex) {
        return Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS);
    }

    private static Predicate<String> full(String regex) {
        Pattern p = re(regex);
        return v -> p.matcher(v).matches();
    }

    private static final Set<String> BOOLEANS = Set.of("true", "false", "yes", "no", "y", "n", "t", "f");
    private static final Set<String> GENDERS = Set.of("m", "f", "male", "female", "man", "woman", "w");
    private static final Set<String> COUNTRIES = countries();
    private static final Set<String> US_STATES = Set.of(("AL AK AZ AR CA CO CT DE FL GA HI ID IL IN IA KS KY LA ME MD MA "
            + "MI MN MS MO MT NE NV NH NJ NM NY NC ND OH OK OR PA RI SC SD TN TX UT VT VA WA WV WI WY DC").split(" "));

    private static Set<String> countries() {
        List<String> out = new ArrayList<>();
        for (String c : ("Afghanistan Albania Algeria Argentina Armenia Australia Austria Azerbaijan Bahamas Bahrain "
                + "Bangladesh Belarus Belgium Bolivia Bosnia Brazil Bulgaria Cambodia Cameroon Canada Chile China Colombia "
                + "Croatia Cuba Cyprus Czechia Denmark Ecuador Egypt Estonia Ethiopia Finland France Georgia Germany Ghana "
                + "Greece Guatemala Honduras Hungary Iceland India Indonesia Iran Iraq Ireland Israel Italy Jamaica Japan "
                + "Jordan Kazakhstan Kenya Korea Kuwait Latvia Lebanon Libya Lithuania Luxembourg Madagascar Malaysia Mali "
                + "Malta Mexico Moldova Monaco Mongolia Morocco Mozambique Namibia Nepal Netherlands Nicaragua Niger Nigeria "
                + "Norway Oman Pakistan Panama Paraguay Peru Philippines Poland Portugal Qatar Romania Russia Rwanda Senegal "
                + "Serbia Singapore Slovakia Slovenia Somalia Spain Sudan Sweden Switzerland Syria Taiwan Tanzania Thailand "
                + "Tunisia Turkey Uganda Ukraine Uruguay Uzbekistan Venezuela Vietnam Yemen Zambia Zimbabwe USA US UK UAE")
                .split(" ")) {
            out.add(c.toLowerCase(Locale.ROOT));
        }
        out.addAll(List.of("united states", "united kingdom", "united states of america", "south africa", "new zealand",
                "saudi arabia", "costa rica"));
        return Set.copyOf(out);
    }

    private static final Predicate<String> DATE_A = full("\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}[/.]\\d{1,2}[/.]\\d{2,4}|\\d{8}");
    private static final Predicate<String> DATE_B = full("(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])");
    private static final Pattern URL = re("(?i)(https?://|www\\.)[^" + SP + "]+$");
    private static final Predicate<String> FLOAT_A = full(NUM), FLOAT_B = full("[-+]?\\d*\\.\\d+([eE][-+]?\\d+)?");
    private static final Predicate<String> PHONE = full("\\+?[\\d" + SP + "().-]{7,20}");

    private static final List<Rule> RULES = List.of(
            new Rule("boolean", v -> BOOLEANS.contains(lower(v))),
            new Rule("gender", v -> GENDERS.contains(lower(v))),
            new Rule("email", full("[^@" + SP + "]+@[^@" + SP + "]+\\.[A-Za-z]{2,}")),
            new Rule("url", v -> URL.matcher(v).lookingAt()),
            new Rule("datetime", full("\\d{4}-\\d{2}-\\d{2}[ T]\\d{1,2}:\\d{2}(:\\d{2})?(\\.\\d+)?Z?")),
            new Rule("date", v -> DATE_A.test(v) && !allDigits(v) || DATE_B.test(v)),
            new Rule("time", full("\\d{1,2}:\\d{2}(:\\d{2})?( ?[AaPp][Mm])?")),
            new Rule("year", full("(18|19|20)\\d{2}")),
            new Rule("percent", full(NUM + " ?%")),
            new Rule("currency", full("[$\u20ac\u00a3\u00a5] ?" + NUM + "|" + NUM + " ?(USD|EUR|GBP|[$\u20ac\u00a3])")),
            new Rule("integer", full("[-+]?\\d+")),
            new Rule("float", v -> FLOAT_A.test(v) || FLOAT_B.test(v)),
            new Rule("phone", v -> {
                if (!PHONE.test(v)) return false;
                long digits = v.codePoints().filter(ColumnClasses::isDigit).count();
                return digits >= 7 && digits <= 15 && v.chars().anyMatch(c -> "+-(). ".indexOf(c) >= 0);
            }),
            new Rule("postal", full("\\d{5}-\\d{4}|[A-Z]\\d[A-Z] ?\\d[A-Z]\\d|[A-Z]{1,2}\\d[A-Z\\d]? ?\\d[A-Z]{2}")),
            new Rule("country", v -> COUNTRIES.contains(lower(v))),
            new Rule("us_state", US_STATES::contains),
            new Rule("code", full("[A-Z0-9_-]{1,8}")),
            new Rule("name_like", full("[A-Z][a-z'\u2019-]+( [A-Z][a-z'\u2019.-]*){0,3}")),
            new Rule("text", v -> words(v) >= 4));

    private static String lower(String v) {
        return v.toLowerCase(Locale.ROOT);
    }

    /** str.isdigit on the whole (non-empty) string. */
    private static boolean allDigits(String v) {
        return !v.isEmpty() && v.codePoints().allMatch(ColumnClasses::isDigit);
    }

    /** str.strip(): leading and trailing str.isspace characters. */
    static String strip(String v) {
        int start = 0, end = v.length();
        while (start < end) {
            int c = v.codePointAt(start);
            if (!isSpace(c)) break;
            start += Character.charCount(c);
        }
        while (end > start) {
            int c = v.codePointBefore(end);
            if (!isSpace(c)) break;
            end -= Character.charCount(c);
        }
        return v.substring(start, end);
    }

    /** len(str.split()): runs of str.isspace characters separate words. */
    private static int words(String v) {
        int count = 0;
        boolean inWord = false;
        for (int k = 0; k < v.length(); ) {
            int c = v.codePointAt(k);
            k += Character.charCount(c);
            if (isSpace(c)) {
                inWord = false;
            } else if (!inWord) {
                inWord = true;
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------------------------------- class edges

    /**
     * The class edges of one table in SF's extra-edge line format ("label TAB table TAB column TAB @class:value TAB
     * 1.0"), in column order. Shuffled: the same classes permuted within the table with Python's random.Random seeded
     * by sha256("dataset/scenario/side/table/family").hexdigest(), as the study's full-panel class files
     * (sf-matcher-study servers/make_classfull_configs.py).
     */
    static List<String> edgeLines(String dataset, String scenario, String side, Table table, Kind kind, boolean shuffled) {
        List<String> classes = new ArrayList<>();
        for (Column column : table.getColumns()) classes.add(columnClass(column.getValues(), kind));
        if (shuffled) {
            String key = dataset + "/" + scenario + "/" + side + "/" + table.getName() + "/" + kind.family;
            new StudyRandom.Python(StudyRandom.sha256Hex(key)).shuffle(classes);
        }
        List<String> lines = new ArrayList<>();
        for (int c = 0; c < classes.size(); c++) {
            lines.add(kind.label + "\t" + table.getName() + "\t" + table.getColumns().get(c).getLabel() + "\t@class:"
                    + classes.get(c) + "\t1.0");
        }
        return lines;
    }
}
