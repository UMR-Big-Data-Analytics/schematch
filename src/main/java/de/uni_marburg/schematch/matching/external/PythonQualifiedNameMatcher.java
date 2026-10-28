package de.uni_marburg.schematch.matching.external;

/** Base plus table-qualified-name Q; native equivalent is QualifiedNameRefinementMatcher when tie_noise is zero. Requires study_server.py (default port 5102). */
public final class PythonQualifiedNameMatcher extends NamedScenarioPythonMatcher {
    public PythonQualifiedNameMatcher() { super("q"); }
}
