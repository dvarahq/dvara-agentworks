package com.dvarahq.agentworks.runplane.exposure;

import com.dvarahq.agentworks.exposure.ExposurePolicy;
import com.dvarahq.agentworks.exposure.ExposureSpec;
import com.dvarahq.agentworks.exposure.ExposureType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalExposureDescriptorGeneratorTest {
    @Test
    void derivesDescriptorFromPinnedManifestAndCanonicalSchemas() {
        UUID definition = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        String manifest = "{\"manifestVersion\":9,\"agent\":{\"definitionId\":\"" + definition
                + "\",\"versionId\":\"" + version + "\",\"version\":\"1.2.3\",\"specDigest\":\"sha256:"
                + "a".repeat(64) + "\"}}";
        ExposureSpec exposure = new ExposureSpec("agent-card", ExposureType.A2A, null,
                new ExposurePolicy("oauth@1", Set.of("agent.invoke"), 60, 1024, 4096, Set.of("PUBLIC"), false), Map.of());

        Map<String, Object> descriptor = new CanonicalExposureDescriptorGenerator().generate(exposure, manifest,
                "{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\"}");

        assertThat(descriptor).containsEntry("agentDefinitionId", definition.toString())
                .containsEntry("agentVersionId", version.toString()).containsEntry("exposureType", "A2A")
                .containsEntry("authentication", "oauth@1").containsEntry("streaming", true);
        assertThat(descriptor.get("manifestDigest").toString()).matches("sha256:[0-9a-f]{64}");
    }
}
