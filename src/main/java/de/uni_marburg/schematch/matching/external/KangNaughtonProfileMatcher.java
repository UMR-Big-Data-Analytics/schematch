package de.uni_marburg.schematch.matching.external;

/** Sorted MI-profile variant of the project's Kang–Naughton reconstruction. Served by seed_server.py (default port 5101). */
public final class KangNaughtonProfileMatcher extends NamedPythonMatcher {
    public KangNaughtonProfileMatcher() { super("kang_naughton_profile"); }
}
