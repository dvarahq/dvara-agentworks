package com.dvarahq.agentworks.spec;

import com.dvarahq.agentworks.spec.model.AgentSpec;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * A validated specification with its canonical JSON (sorted keys, no insignificant whitespace, absent optional
 * fields omitted, normalized numbers) and {@code sha256:} digest over the UTF-8 canonical bytes.
 */
public record CanonicalAgentSpec(AgentSpec spec, String canonicalJson, String digest) {
    public CanonicalAgentSpec {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(canonicalJson, "canonicalJson");
        Objects.requireNonNull(digest, "digest");
    }

    public byte[] canonicalBytes() {
        return canonicalJson.getBytes(StandardCharsets.UTF_8);
    }
}
