package de.uni_marburg.schematch.matching.external;

/** Snoopy semantic-join proxy columns (released WikiTable/WDC/opendata checkpoints) over fastText cc.en.300 cell vectors; a join signal, for internal comparison only (the repository has no licence). Needs options.checkpoint_dir / SCHEMATCH_SNOOPY and options.vectors / SCHEMATCH_FASTTEXT. Served by seed_server.py (default port 5101). */
public final class SnoopyMatcher extends NamedPythonMatcher {
    public SnoopyMatcher() { super("snoopy"); }
}
