package de.uni_marburg.schematch.matching.external;

/** Common table-pair transport for named Python algorithms; no model download or training is performed by this class. */
public abstract class NamedPythonMatcher extends PythonServerMatcher {
    protected NamedPythonMatcher(String method) { super.setMethod(method); }

    @Override
    public final void setMethod(String method) {
        if (!getMethod().equals(method)) throw new IllegalArgumentException("Use PythonServerMatcher to change algorithm");
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(method=" + getMethod() + ", serverPort=" + getServerPort()
                + ", options=" + getOptions() + ", timeoutSeconds=" + getTimeoutSeconds()
                + ", failOnError=" + getFailOnError() + ")";
    }
}
