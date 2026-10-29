package com.dvarahq.agentworks.capability;

import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Authored source declaration. It carries references only; fetched bytes and resolved evidence are separate. */
public record SkillSource(String id, SkillSourceKind kind, URI uri, String versionRef, String expectedDigest,
        String signatureDigest, String trustPolicy, String manifestVersion, SkillLifecycle lifecycle,
        SkillLoadingPolicy loadingPolicy, Set<String> contextTriggers) {
    private static final Pattern ID = Pattern.compile("^[a-z0-9]([a-z0-9._-]*[a-z0-9])?$");
    private static final Pattern DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");

    public SkillSource {
        if (id == null || id.length() > 128 || !ID.matcher(id).matches()) throw new IllegalArgumentException("invalid skill id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(uri, "uri");
        bounded(versionRef, "versionRef", 256);
        if (expectedDigest == null || !DIGEST.matcher(expectedDigest).matches()) {
            throw new IllegalArgumentException("skill expectedDigest must be sha256");
        }
        if (signatureDigest != null && !DIGEST.matcher(signatureDigest).matches()) {
            throw new IllegalArgumentException("skill signatureDigest must be sha256");
        }
        bounded(trustPolicy, "trustPolicy", 128);
        bounded(manifestVersion, "manifestVersion", 64);
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(loadingPolicy, "loadingPolicy");
        contextTriggers = Set.copyOf(contextTriggers == null ? Set.of() : contextTriggers);
        if (loadingPolicy == SkillLoadingPolicy.CONTEXT_TRIGGERED && contextTriggers.isEmpty()) {
            throw new IllegalArgumentException("context-triggered skill requires triggers");
        }
    }

    private static void bounded(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " must be 1 to " + max + " characters");
        }
    }
}
