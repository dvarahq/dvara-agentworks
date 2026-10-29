package com.dvarahq.agentworks.capability;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Deterministic progressive loading. ON_DEMAND skills require their id; triggers never cause all skills to load. */
public final class SkillContextLoader {
    public List<LoadedSkill> load(List<ResolvedSkill> skills, Set<String> requestedIds, Set<String> contextSignals,
            long byteBudget) {
        if (byteBudget < 0) throw new IllegalArgumentException("byte budget cannot be negative");
        List<LoadedSkill> loaded = new ArrayList<>();
        long used = 0;
        for (ResolvedSkill skill : skills.stream().sorted(Comparator.comparing(ResolvedSkill::id)).toList()) {
            boolean selected = switch (skill.loadingPolicy()) {
                case ALWAYS -> true;
                case ON_DEMAND -> requestedIds.contains(skill.id());
                case CONTEXT_TRIGGERED -> skill.contextTriggers().stream().anyMatch(contextSignals::contains);
            };
            if (!selected) continue;
            byte[] content = skill.skillMarkdown();
            if (byteBudget - used < content.length) continue;
            used += content.length;
            loaded.add(new LoadedSkill(skill.id(), skill.contentDigest(), new String(content, StandardCharsets.UTF_8)));
        }
        return List.copyOf(loaded);
    }

    public record LoadedSkill(String id, String contentDigest, String markdown) {}
}
