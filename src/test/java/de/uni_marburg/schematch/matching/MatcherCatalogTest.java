package de.uni_marburg.schematch.matching;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.uni_marburg.schematch.utils.Configuration;
import de.uni_marburg.schematch.preprocessing.tokenization.TokenizerFactory;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Checks the complete audited panel through the same factory/configuration path as YAML, without running models. */
class MatcherCatalogTest {
    @Test
    void allPublishedClassesAndAuditedSeedParametersAreConstructible() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (InputStream input = getClass().getResourceAsStream("/matcher_catalog.json")) {
            assertNotNull(input);
            JsonNode catalog = json.readTree(input);
            MatcherFactory factory = new MatcherFactory();
            for (JsonNode entry : catalog.get("matchers")) {
                String name = entry.get("name").asText(), pkg = entry.get("package").asText();
                Matcher matcher = factory.createMatcherInstance(new Configuration.MatcherConfiguration(name,pkg,Map.of()));
                assertEquals(name,matcher.getClass().getSimpleName());
                if (matcher instanceof TokenizedTablePairMatcher tokenized) {
                    tokenized.setTokenizer(new TokenizerFactory().createTokenizerInstance(
                            new Configuration.TokenizerConfiguration("nGramTokenizer",Map.of("n",3))));
                }
                assertTrue(matcher.toString().contains(name), name + " missing from result identity");
            }
            for (JsonNode entry : catalog.get("parameter_cases")) {
                Map<String,Object> params = json.convertValue(entry.get("params"),new TypeReference<>() {});
                String name = entry.get("name").asText(), pkg = entry.get("package").asText();
                assertNotNull(factory.createMatcherInstance(new Configuration.MatcherConfiguration(name,pkg,params)),
                        entry.get("seed").asText());
            }
        }
    }
}
