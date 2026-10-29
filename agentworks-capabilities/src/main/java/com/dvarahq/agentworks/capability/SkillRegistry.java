package com.dvarahq.agentworks.capability;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.util.List;

/** Workspace-scoped registry of verified skill source versions. */
public interface SkillRegistry {
    List<ResolvedSkill> activeVersions(WorkspaceId workspace, String id);

    static SkillRegistry empty() { return (workspace, id) -> List.of(); }
}
