package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.artifact.ArtifactStore;
import com.dvarahq.agentworks.domain.artifact.ArtifactClassification;
import com.dvarahq.agentworks.domain.artifact.ArtifactDigest;
import com.dvarahq.agentworks.domain.artifact.ArtifactOwner;
import com.dvarahq.agentworks.domain.artifact.ArtifactProvenance;
import com.dvarahq.agentworks.domain.artifact.ArtifactRef;
import com.dvarahq.agentworks.domain.artifact.ArtifactRunBinding;
import com.dvarahq.agentworks.domain.artifact.ArtifactWrite;
import com.dvarahq.agentworks.domain.run.RunInvoker;
import com.dvarahq.agentworks.domain.run.RunPins;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Offloads a large effective tool result and returns a bounded content-free reference summary to the model. */
final class ToolResultArtifactBinding {
    static final int DEFAULT_INLINE_BYTES = 32 * 1024;

    private final ArtifactStore store;
    private final ArtifactOwner owner;
    private final ArtifactRunBinding run;
    private final String actor;
    private final int inlineBytes;

    ToolResultArtifactBinding(ArtifactStore store, RunPins pins, RunInvoker invoker) {
        this(store, pins, invoker, DEFAULT_INLINE_BYTES);
    }

    ToolResultArtifactBinding(ArtifactStore store, RunPins pins, RunInvoker invoker, int inlineBytes) {
        this.store = Objects.requireNonNull(store, "store");
        Objects.requireNonNull(pins, "pins");
        Objects.requireNonNull(invoker, "invoker");
        if (!pins.workspaceId().equals(invoker.workspaceId())) {
            throw new IllegalArgumentException("tool artifact owner does not match the run workspace");
        }
        if (inlineBytes < 0 || inlineBytes > 1024 * 1024) {
            throw new IllegalArgumentException("inlineBytes must be between zero and one MiB");
        }
        this.owner = new ArtifactOwner(invoker.organizationId(), pins.workspaceId());
        this.run = new ArtifactRunBinding(pins.id(), pins.releaseId(), pins.revisionId(), pins.cohortId());
        this.actor = invoker.subjectId().toString();
        this.inlineBytes = inlineBytes;
    }

    String offloadIfLarge(String result) {
        return offload(result, false);
    }

    String offload(String result, boolean required) {
        Objects.requireNonNull(result, "result");
        byte[] content = result.getBytes(StandardCharsets.UTF_8);
        if (!required && content.length <= inlineBytes) return result;
        String digest = digest(content);
        ArtifactRef reference = store.put(new ArtifactWrite(owner, "text/plain", content.length,
                new ArtifactDigest(digest), null, ArtifactClassification.CONFIDENTIAL, false,
                new ArtifactProvenance("TOOL_RESULT", actor, run, java.util.List.of()),
                new ByteArrayInputStream(content)));
        return "{\"artifactRef\":\"" + reference.value() + "\",\"mediaType\":\"text/plain\",\"bytes\":"
                + content.length + ",\"digest\":\"" + digest + "\"}";
    }

    private static String digest(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
