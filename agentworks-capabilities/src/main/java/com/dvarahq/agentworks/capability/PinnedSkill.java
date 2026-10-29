package com.dvarahq.agentworks.capability;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Release-manifest skill evidence. Content stays in its verified source/cache and is loaded only by policy. */
public record PinnedSkill(String id, SkillSourceKind sourceKind, String sourceUri, String authoredRef,
        String resolvedRef, String contentDigest, String signatureDigest, String trustPolicy, String manifestVersion,
        SkillLoadingPolicy loadingPolicy, Set<String> contextTriggers, String license, String provenance) {
    private static final Pattern ID = Pattern.compile("^[a-z0-9]([a-z0-9._-]*[a-z0-9])?$");
    private static final Pattern DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern COMMIT = Pattern.compile("^[0-9a-f]{40}$");

    public PinnedSkill {
        if (id == null || id.length() > 128 || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid pinned skill id");
        }
        Objects.requireNonNull(sourceKind, "sourceKind");
        URI uri;
        try {
            uri = URI.create(bounded(sourceUri, "sourceUri", 2_048));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid pinned skill source URI", invalid);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String expectedScheme = switch (sourceKind) {
            case LOCAL -> "file";
            case GIT -> "git+https";
            case HTTP -> "https";
            case OCI -> "oci";
        };
        if (!expectedScheme.equals(scheme) || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("pinned skill source URI does not match its kind");
        }
        authoredRef = bounded(authoredRef, "authoredRef", 256);
        resolvedRef = bounded(resolvedRef, "resolvedRef", 256);
        if ((sourceKind == SkillSourceKind.GIT && !COMMIT.matcher(resolvedRef).matches())
                || (sourceKind == SkillSourceKind.OCI && !DIGEST.matcher(resolvedRef).matches())) {
            throw new IllegalArgumentException("pinned skill resolved reference is not immutable");
        }
        if (contentDigest == null || !DIGEST.matcher(contentDigest).matches()
                || (signatureDigest != null && !DIGEST.matcher(signatureDigest).matches())) {
            throw new IllegalArgumentException("invalid pinned skill digest");
        }
        trustPolicy = bounded(trustPolicy, "trustPolicy", 128);
        manifestVersion = bounded(manifestVersion, "manifestVersion", 64);
        Objects.requireNonNull(loadingPolicy, "loadingPolicy");
        contextTriggers = Set.copyOf(contextTriggers == null ? Set.of() : contextTriggers);
        if (contextTriggers.size() > 128 || contextTriggers.stream().anyMatch(value -> value == null
                || value.isBlank() || value.length() > 128)) {
            throw new IllegalArgumentException("invalid pinned skill context triggers");
        }
        if (loadingPolicy == SkillLoadingPolicy.CONTEXT_TRIGGERED && contextTriggers.isEmpty()) {
            throw new IllegalArgumentException("context-triggered pinned skill requires triggers");
        }
        license = bounded(license, "license", 256);
        provenance = bounded(provenance, "provenance", 2_048);
    }

    private static String bounded(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " must be 1 to " + max + " characters");
        }
        return value;
    }

    public static PinnedSkill from(ResolvedSkill skill) {
        return new PinnedSkill(skill.id(), skill.sourceKind(), skill.sourceUri().toASCIIString(), skill.authoredRef(),
                skill.resolvedRef(), skill.contentDigest(), skill.signatureDigest(), skill.trustPolicy(),
                skill.manifestVersion(), skill.loadingPolicy(), skill.contextTriggers(), skill.license(), skill.provenance());
    }
}
