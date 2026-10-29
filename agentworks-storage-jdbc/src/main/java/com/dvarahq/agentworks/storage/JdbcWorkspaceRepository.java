package com.dvarahq.agentworks.storage;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Small tenant-aware repository used to establish the schema-V1 access contract. */
public final class JdbcWorkspaceRepository {
    private final JdbcTemplate jdbc;
    public JdbcWorkspaceRepository(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc, "jdbc"); }

    public Optional<WorkspaceView> findCurrent() {
        UUID workspaceId = TenantContext.requireWorkspace().value();
        List<WorkspaceView> rows = jdbc.query("""
                select id, organization_id, name from workspace where id = ?
                """, (rs, row) -> new WorkspaceView(rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class), rs.getString("name")), workspaceId);
        return rows.stream().findFirst();
    }

    public record WorkspaceView(UUID id, UUID organizationId, String name) {}
}
