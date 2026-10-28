package de.uni_marburg.schematch.matching.external;

/** PDED (graduated-assignment graph matching over FD graphs) through its own Java runner, with its distribution blend
 * (options.weight, default 0.65 as in the harness). Single-table scenarios only. Needs the PDED build (options.pded_home or
 * SCHEMATCH_PDED: target/classes plus cp.txt) and a Java runtime (options.java or SCHEMATCH_JAVA). Standalone: initialMatrix
 * defaults to SERVER. Requires study_server.py (default port 5102). */
public final class PdedMatcher extends NamedScenarioPythonMatcher {
    public PdedMatcher() {
        super("pded");
        setInitialMatrix("SERVER");
    }
}
