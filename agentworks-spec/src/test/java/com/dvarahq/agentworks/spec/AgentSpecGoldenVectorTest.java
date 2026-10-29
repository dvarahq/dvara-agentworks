package com.dvarahq.agentworks.spec;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/** Golden vectors: YAML and JSON authoring normalize to identical canonical bytes and a pinned digest. */
class AgentSpecGoldenVectorTest {
    private final AgentSpecCodec codec = AgentSpecCodec.create();

    @ParameterizedTest
    @CsvSource({"software-engineer", "minimal-remote"})
    void yamlAndJsonNormalizeToOnePinnedCanonicalRepresentation(String vector) throws IOException {
        CanonicalAgentSpec fromYaml = codec.read(resource(vector + ".yaml"), SpecFormat.YAML);
        CanonicalAgentSpec fromJson = codec.read(resource(vector + ".json"), SpecFormat.JSON);
        String expectedCanonical = new String(resource(vector + ".canonical.json"), StandardCharsets.UTF_8).strip();
        String expectedDigest = new String(resource(vector + ".digest"), StandardCharsets.UTF_8).strip();

        assertThat(fromYaml.canonicalJson()).isEqualTo(expectedCanonical);
        assertThat(fromJson.canonicalJson()).isEqualTo(expectedCanonical);
        assertThat(fromYaml.digest()).isEqualTo(expectedDigest).isEqualTo(fromJson.digest());
        assertThat(AgentSpecCodec.digest(expectedCanonical)).isEqualTo(expectedDigest);
        assertThat(fromYaml.spec()).isEqualTo(fromJson.spec());
    }

    @ParameterizedTest
    @CsvSource({"software-engineer", "minimal-remote"})
    void canonicalFormIsAFixedPointAcrossEncodings(String vector) throws IOException {
        CanonicalAgentSpec original = codec.read(resource(vector + ".yaml"), SpecFormat.YAML);

        CanonicalAgentSpec reread = codec.read(original.canonicalBytes(), SpecFormat.JSON);
        CanonicalAgentSpec viaYaml = codec.read(codec.toYaml(original.spec()).getBytes(StandardCharsets.UTF_8),
                SpecFormat.YAML);
        CanonicalAgentSpec viaSdk = codec.canonicalize(original.spec());

        assertThat(reread).isEqualTo(original);
        assertThat(viaYaml).isEqualTo(original);
        assertThat(viaSdk).isEqualTo(original);
    }

    static byte[] resource(String name) throws IOException {
        try (InputStream input = AgentSpecGoldenVectorTest.class.getResourceAsStream("/agentspec/golden/" + name)) {
            return Objects.requireNonNull(input, name).readAllBytes();
        }
    }
}
