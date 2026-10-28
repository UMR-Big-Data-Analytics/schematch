package de.uni_marburg.schematch.matching.external;

/** One request per whole scenario, sharing the existing study-server protocol. Configure as a second-line matcher. */
public abstract class NamedScenarioPythonMatcher extends ScenarioPythonServerMatcher {
    protected NamedScenarioPythonMatcher(String method) { super.setMethod(method); }

    @Override
    public final void setMethod(String method) {
        if (!getMethod().equals(method)) throw new IllegalArgumentException("Use ScenarioPythonServerMatcher to change algorithm");
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(method=" + getMethod() + ", serverPort=" + getServerPort()
                + ", initialMatrix=" + getInitialMatrix() + ", baseMatcherIndex=" + getBaseMatcherIndex()
                + ", options=" + getOptions() + ", timeoutSeconds=" + getTimeoutSeconds()
                + ", failOnError=" + getFailOnError() + ")";
    }
}
