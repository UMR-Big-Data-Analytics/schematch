package de.uni_marburg.schematch.matching.external;

/** Editex phonetic edit similarity (Zobel and Dart 1996) of labels. Served by seed_server.py (default port 5101). */
public final class EditexMatcher extends NamedPythonMatcher {
    public EditexMatcher() { super("editex"); }
}
