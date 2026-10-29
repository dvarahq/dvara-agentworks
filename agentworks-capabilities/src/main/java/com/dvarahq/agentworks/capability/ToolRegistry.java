package com.dvarahq.agentworks.capability;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Tenant-scoped tool discovery. Invocation authorization is a separate #39 boundary. */
public interface ToolRegistry {
    Optional<ToolSummary> get(WorkspaceId workspace, ToolId id);
    Page search(WorkspaceId workspace, Search request);
    List<AgentTool> details(WorkspaceId workspace, Set<ToolId> ids);

    record Search(String query, Set<ToolType> types, Set<String> tags, String cursor, int limit) {
        public Search {
            query = query == null ? "" : query.strip();
            if (query.length() > 256) throw new IllegalArgumentException("query is too long");
            types = Set.copyOf(Objects.requireNonNull(types, "types"));
            tags = Set.copyOf(Objects.requireNonNull(tags, "tags"));
            if (tags.size() > 32 || tags.stream().anyMatch(tag -> tag == null || tag.isBlank() || tag.length() > 64)) {
                throw new IllegalArgumentException("invalid tag filter");
            }
            if (cursor != null && cursor.length() > 512) throw new IllegalArgumentException("invalid cursor");
            if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit is 1 to 200");
        }
    }

    record Page(List<ToolSummary> items, String nextCursor) {
        public Page { items = List.copyOf(Objects.requireNonNull(items, "items")); }
    }
}
