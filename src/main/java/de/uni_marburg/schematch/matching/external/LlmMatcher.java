package de.uni_marburg.schematch.matching.external;

/** Optional LLM matcher; the backend's existing service/resource gates remain in force. Served by seed_server.py (default port 5101). */
public final class LlmMatcher extends NamedPythonMatcher {
    public LlmMatcher() { super("llm"); }
}
