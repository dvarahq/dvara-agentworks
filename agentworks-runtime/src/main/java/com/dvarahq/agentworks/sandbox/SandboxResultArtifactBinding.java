package com.dvarahq.agentworks.sandbox;

import com.dvarahq.agentworks.artifact.ArtifactStore;
import com.dvarahq.agentworks.domain.artifact.ArtifactClassification;
import com.dvarahq.agentworks.domain.artifact.ArtifactDigest;
import com.dvarahq.agentworks.domain.artifact.ArtifactOwner;
import com.dvarahq.agentworks.domain.artifact.ArtifactProvenance;
import com.dvarahq.agentworks.domain.artifact.ArtifactRef;
import com.dvarahq.agentworks.domain.artifact.ArtifactRunBinding;
import com.dvarahq.agentworks.domain.artifact.ArtifactWrite;
import com.dvarahq.agentworks.domain.sandbox.SandboxOwner;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Converts a large sandbox command result to an immutable artifact and a bounded content-free reference. */
final class SandboxResultArtifactBinding {
    static final int DEFAULT_INLINE_BYTES = 32 * 1024;

    private final ArtifactStore store;
    private final SandboxOwner owner;
    private final String actor;
    private final int inlineBytes;

    SandboxResultArtifactBinding(ArtifactStore store, SandboxOwner owner, String actor) {
        this(store, owner, actor, DEFAULT_INLINE_BYTES);
    }

    SandboxResultArtifactBinding(ArtifactStore store, SandboxOwner owner, String actor, int inlineBytes) {
        this.store = Objects.requireNonNull(store, "store");
        this.owner = Objects.requireNonNull(owner, "owner");
        if (actor == null || actor.isBlank() || actor.length() > 256) {
            throw new IllegalArgumentException("sandbox artifact actor is invalid");
        }
        if (inlineBytes < 0 || inlineBytes > 1024 * 1024) {
            throw new IllegalArgumentException("inlineBytes must be between zero and one MiB");
        }
        this.actor = actor;
        this.inlineBytes = inlineBytes;
    }

    String offloadIfLarge(String result) {
        Objects.requireNonNull(result, "result");
        byte[] content = result.getBytes(StandardCharsets.UTF_8);
        if (content.length <= inlineBytes) return result;
        String digest = digest(content);
        ArtifactRef reference = store.put(new ArtifactWrite(
                new ArtifactOwner(owner.organizationId(), owner.workspaceId()), "text/plain", content.length,
                new ArtifactDigest(digest), null, ArtifactClassification.CONFIDENTIAL, false,
                new ArtifactProvenance("SANDBOX_OUTPUT", actor,
                        new ArtifactRunBinding(owner.runId(), owner.releaseId(), owner.deploymentRevisionId(),
                                owner.cohortId()), java.util.List.of()),
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
