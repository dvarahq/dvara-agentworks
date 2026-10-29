package com.dvarahq.agentworks.storage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Workspace-scoped persistence for AgentDefinition lineage and published AgentVersions. Every statement is bound to
 * the transaction's workspace; there is intentionally no update or delete operation for versions.
 */
public final class JdbcAgentDefinitionRepository {
    private static final String DEFINITION_COLUMNS = "id, workspace_id, name, description, created_at";
    private static final String VERSION_COLUMNS = "id, workspace_id, definition_id, sequence_number, version_label, "
            + "api_version, spec_json, spec_digest, created_at";
    private static final RowMapper<DefinitionRow> DEFINITIONS = (rs, row) -> new DefinitionRow(
            rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class), rs.getString("name"),
            rs.getString("description"), rs.getObject("created_at", OffsetDateTime.class).toInstant());
    private static final RowMapper<VersionRow> VERSIONS = (rs, row) -> new VersionRow(
            rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
            rs.getObject("definition_id", UUID.class), rs.getLong("sequence_number"), rs.getString("version_label"),
            rs.getString("api_version"), rs.getString("spec_json"), rs.getString("spec_digest"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public JdbcAgentDefinitionRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public DefinitionRow insertDefinition(UUID id, String name, String description, Instant createdAt) {
        UUID workspaceId = workspace();
        createdAt = storedPrecision(createdAt);
        jdbc.update("insert into agent_definition(workspace_id, id, name, description, created_at) values (?, ?, ?, ?, ?)",
                workspaceId, id, name, description, timestamp(createdAt));
        return new DefinitionRow(id, workspaceId, name, description, createdAt);
    }

    public Optional<DefinitionRow> findDefinition(UUID id) {
        return jdbc.query("select " + DEFINITION_COLUMNS + " from agent_definition where workspace_id = ? and id = ?",
                DEFINITIONS, workspace(), id).stream().findFirst();
    }

    public Optional<DefinitionRow> findDefinitionByName(String name) {
        return jdbc.query("select " + DEFINITION_COLUMNS + " from agent_definition where workspace_id = ? and name = ?",
                DEFINITIONS, workspace(), name).stream().findFirst();
    }

    /** Reads and row-locks a definition so publications to one lineage are serialized. */
    public Optional<DefinitionRow> lockDefinition(UUID id) {
        return jdbc.query("select " + DEFINITION_COLUMNS
                        + " from agent_definition where workspace_id = ? and id = ? for update",
                DEFINITIONS, workspace(), id).stream().findFirst();
    }

    public List<DefinitionRow> listDefinitions() {
        return jdbc.query("select " + DEFINITION_COLUMNS + " from agent_definition where workspace_id = ? order by name",
                DEFINITIONS, workspace());
    }

    /**
     * One keyset page of definitions ordered by name, then id, starting strictly after {@code (afterName, afterId)}
     * when given (issue #35).
     */
    public List<DefinitionRow> listDefinitions(String afterName, UUID afterId, int limit) {
        if ((afterName == null) != (afterId == null)) throw new IllegalArgumentException("a position names both keys");
        if (afterName == null) {
            return jdbc.query("select " + DEFINITION_COLUMNS + " from agent_definition where workspace_id = ? "
                    + "order by name, id fetch first " + limit + " rows only", DEFINITIONS, workspace());
        }
        return jdbc.query("select " + DEFINITION_COLUMNS + " from agent_definition where workspace_id = ? "
                + "and (name, id) > (?, ?) order by name, id fetch first " + limit + " rows only",
                DEFINITIONS, workspace(), afterName, afterId);
    }

    public VersionRow insertVersion(UUID id, UUID definitionId, long sequence, String versionLabel, String apiVersion,
            String specJson, String specDigest, Instant createdAt) {
        UUID workspaceId = workspace();
        createdAt = storedPrecision(createdAt);
        jdbc.update("insert into agent_version(" + VERSION_COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, workspaceId, definitionId, sequence, versionLabel, apiVersion, specJson, specDigest,
                timestamp(createdAt));
        return new VersionRow(id, workspaceId, definitionId, sequence, versionLabel, apiVersion, specJson, specDigest,
                createdAt);
    }

    public Optional<VersionRow> findVersion(UUID definitionId, String versionLabel) {
        return jdbc.query("select " + VERSION_COLUMNS
                        + " from agent_version where workspace_id = ? and definition_id = ? and version_label = ?",
                VERSIONS, workspace(), definitionId, versionLabel).stream().findFirst();
    }

    public Optional<VersionRow> findVersionById(UUID versionId) {
        return jdbc.query("select " + VERSION_COLUMNS + " from agent_version where workspace_id = ? and id = ?",
                VERSIONS, workspace(), versionId).stream().findFirst();
    }

    public List<VersionRow> listVersions(UUID definitionId) {
        return jdbc.query("select " + VERSION_COLUMNS
                        + " from agent_version where workspace_id = ? and definition_id = ? order by sequence_number",
                VERSIONS, workspace(), definitionId);
    }

    /** One keyset page of a definition's versions in publication order, after {@code afterSequence} (issue #35). */
    public List<VersionRow> listVersions(UUID definitionId, long afterSequence, int limit) {
        return jdbc.query("select " + VERSION_COLUMNS + " from agent_version where workspace_id = ? and definition_id = ? "
                        + "and sequence_number > ? order by sequence_number fetch first " + limit + " rows only",
                VERSIONS, workspace(), definitionId, afterSequence);
    }

    public long nextSequence(UUID definitionId) {
        Long next = jdbc.queryForObject("select coalesce(max(sequence_number), 0) + 1 from agent_version "
                + "where workspace_id = ? and definition_id = ?", Long.class, workspace(), definitionId);
        return Objects.requireNonNull(next);
    }

    private static UUID workspace() {
        return TenantContext.requireWorkspace().value();
    }

    private static Instant storedPrecision(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record DefinitionRow(UUID id, UUID workspaceId, String name, String description, Instant createdAt) {}

    public record VersionRow(UUID id, UUID workspaceId, UUID definitionId, long sequence, String versionLabel,
            String apiVersion, String specJson, String specDigest, Instant createdAt) {}
}
