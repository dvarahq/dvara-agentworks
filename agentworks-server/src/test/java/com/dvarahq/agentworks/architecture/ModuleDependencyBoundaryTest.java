package com.dvarahq.agentworks.architecture;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleDependencyBoundaryTest {
    private static final Map<String, Set<String>> ALLOWED = Map.ofEntries(
            Map.entry("agentworks-bom", Set.of("agentworks-api", "agentworks-capabilities", "agentworks-context", "agentworks-memory", "agentworks-agent-test", "agentworks-evaluation", "agentworks-spec",
                    "agentworks-sdk", "agentworks-provider-tck", "agentworks-spring-boot-starter",
                    "agentworks-gateway-api", "agentworks-gateway-openai-compatible", "agentworks-gateway-dvarahq",
                    "agentworks-storage-jdbc", "agentworks-release",
                    "agentworks-deployment", "agentworks-runtime", "agentworks-tools", "agentworks-mcp", "agentworks-agent-a2a",
                    "agentworks-server")),
            Map.entry("agentworks-tools", Set.of("agentworks-runtime")),
            Map.entry("agentworks-mcp", Set.of("agentworks-runtime")),
            Map.entry("agentworks-agent-a2a", Set.of("agentworks-api", "agentworks-runtime")),
            Map.entry("agentworks-api", Set.of()),
            Map.entry("agentworks-capabilities", Set.of("agentworks-api")),
            Map.entry("agentworks-context", Set.of("agentworks-api")),
            Map.entry("agentworks-memory", Set.of("agentworks-api", "agentworks-context")),
            Map.entry("agentworks-agent-test", Set.of("agentworks-gateway-api")),
            Map.entry("agentworks-agent-test-sample", Set.of("agentworks-agent-test")),
            Map.entry("agentworks-evaluation", Set.of()),
            Map.entry("agentworks-spec", Set.of("agentworks-api")),
            Map.entry("agentworks-sdk", Set.of("agentworks-spec")),
            Map.entry("agentworks-provider-tck", Set.of("agentworks-api", "agentworks-sdk")),
            Map.entry("agentworks-spring-boot-starter", Set.of("agentworks-sdk")),
            Map.entry("agentworks-reference-embedded", Set.of("agentworks-api", "agentworks-spring-boot-starter")),
            Map.entry("agentworks-reference-remote", Set.of("agentworks-sdk")),
            // The gateway SPI depends only on the API; adapters depend only on the SPI and API (ADR-009).
            Map.entry("agentworks-gateway-api", Set.of("agentworks-api")),
            Map.entry("agentworks-gateway-openai-compatible", Set.of("agentworks-api", "agentworks-gateway-api")),
            Map.entry("agentworks-gateway-dvarahq", Set.of("agentworks-api", "agentworks-gateway-api",
                    "agentworks-gateway-openai-compatible", "agentworks-mcp", "agentworks-agent-a2a")),
            Map.entry("agentworks-storage-jdbc", Set.of("agentworks-api", "agentworks-capabilities", "agentworks-memory", "agentworks-evaluation")),
            Map.entry("agentworks-release", Set.of("agentworks-api", "agentworks-capabilities", "agentworks-memory", "agentworks-spec",
                    "agentworks-gateway-api", "agentworks-storage-jdbc")),
            Map.entry("agentworks-deployment", Set.of("agentworks-api", "agentworks-storage-jdbc", "agentworks-release")),
            // The Run Plane reads release model pins only through the gateway SPI's strict reader (#32, #33).
            Map.entry("agentworks-runtime", Set.of("agentworks-api", "agentworks-capabilities", "agentworks-gateway-api",
                    "agentworks-context", "agentworks-memory", "agentworks-storage-jdbc")),
            Map.entry("agentworks-server", Set.of("agentworks-api", "agentworks-capabilities", "agentworks-context", "agentworks-memory", "agentworks-evaluation", "agentworks-spec",
                    "agentworks-gateway-api", "agentworks-gateway-openai-compatible", "agentworks-storage-jdbc", "agentworks-release",
                    "agentworks-deployment", "agentworks-runtime")));

    @Test
    void reactorDependenciesMatchTheApprovedModularMonolithDirection() throws Exception {
        Path reactor = Path.of("..").toAbsolutePath().normalize();
        for (var entry : ALLOWED.entrySet()) {
            assertThat(internalDependencies(reactor.resolve(entry.getKey()).resolve("pom.xml")))
                    .as(entry.getKey()).isEqualTo(entry.getValue());
        }
    }

    private static Set<String> internalDependencies(Path pom) throws Exception {
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
        var dependencies = document.getElementsByTagName("dependency");
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index < dependencies.getLength(); index++) {
            Element dependency = (Element) dependencies.item(index);
            if ("com.dvarahq.agentworks".equals(text(dependency, "groupId"))) {
                result.add(text(dependency, "artifactId"));
            }
        }
        return result;
    }

    private static String text(Element parent, String tag) {
        return parent.getElementsByTagName(tag).item(0).getTextContent();
    }
}
