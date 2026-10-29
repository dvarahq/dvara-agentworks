package com.dvarahq.agentworks.capability;

import java.net.URI;
import java.util.Map;
import java.util.Set;

/** Immutable verified skill evidence and bounded package content. */
public record ResolvedSkill(String id, SkillSourceKind sourceKind, URI sourceUri, String authoredRef,
        String resolvedRef, String contentDigest, String signatureDigest, String trustPolicy, String manifestVersion,
        SkillLifecycle lifecycle, SkillLoadingPolicy loadingPolicy, Set<String> contextTriggers,
        String license, String provenance, Map<String, byte[]> entries) {
    public ResolvedSkill {
        contextTriggers = Set.copyOf(contextTriggers);
        entries = entries.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> entry.getValue().clone()));
    }
    @Override public Map<String, byte[]> entries() {
        return entries.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> entry.getValue().clone()));
    }
    public byte[] skillMarkdown() { return entries.get("SKILL.md").clone(); }
}
