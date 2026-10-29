package com.dvarahq.agentworks.storage.capability;

import com.dvarahq.agentworks.capability.AgentTool;
import com.dvarahq.agentworks.capability.CapabilityPack;
import com.dvarahq.agentworks.capability.CapabilityPackRegistry;
import com.dvarahq.agentworks.capability.CapabilityRuntimeCatalog;
import com.dvarahq.agentworks.capability.CapabilityCatalogAdministration;
import com.dvarahq.agentworks.capability.CapabilityLifecycleConflictException;
import com.dvarahq.agentworks.capability.CapabilityNotFoundException;
import com.dvarahq.agentworks.capability.ToolId;
import com.dvarahq.agentworks.capability.ToolEffect;
import com.dvarahq.agentworks.capability.ToolRegistry;
import com.dvarahq.agentworks.capability.ToolSummary;
import com.dvarahq.agentworks.capability.ToolType;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.domain.security.PermissionRisk;
import com.dvarahq.agentworks.storage.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** JDBC reference registry. Every operation requires the workspace argument and transaction binding to agree. */
public final class JdbcCapabilityRegistry implements ToolRegistry, CapabilityPackRegistry, CapabilityCatalogAdministration,
        CapabilityRuntimeCatalog {
    private static final String SUMMARY_COLUMNS = "v.namespace, v.name, v.version, v.tool_type, v.summary, "
            + "v.schema_hash, v.effect_resource, v.effect_action, v.effect_risk, "
            + "v.summary_tokens, v.detail_tokens, l.state";
    private static final String DETAIL_COLUMNS = SUMMARY_COLUMNS + ", v.description, v.input_schema";
    private static final RowMapper<SummaryRow> SUMMARIES = (rs, row) -> new SummaryRow(
            new ToolId(rs.getString("namespace"), rs.getString("name"), rs.getString("version")),
            ToolType.valueOf(rs.getString("tool_type")), rs.getString("summary"), rs.getString("schema_hash"),
            new ToolEffect(rs.getString("effect_resource"), rs.getString("effect_action"),
                    PermissionRisk.valueOf(rs.getString("effect_risk"))),
            rs.getInt("summary_tokens"), rs.getInt("detail_tokens"),
            AgentTool.Availability.valueOf(rs.getString("state")));
    private static final RowMapper<DetailRow> DETAILS = (rs, row) -> new DetailRow(
            new ToolId(rs.getString("namespace"), rs.getString("name"), rs.getString("version")),
            ToolType.valueOf(rs.getString("tool_type")), rs.getString("summary"), rs.getString("description"),
            rs.getString("input_schema"), rs.getString("schema_hash"),
            new ToolEffect(rs.getString("effect_resource"), rs.getString("effect_action"),
                    PermissionRisk.valueOf(rs.getString("effect_risk"))), rs.getInt("summary_tokens"),
            rs.getInt("detail_tokens"), AgentTool.Availability.valueOf(rs.getString("state")));

    private final JdbcTemplate jdbc;

    public JdbcCapabilityRegistry(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<ToolSummary> get(WorkspaceId workspace, ToolId id) {
        UUID tenant = workspace(workspace);
        List<SummaryRow> rows = jdbc.query("select " + SUMMARY_COLUMNS + " from agent_tool_version v "
                        + "join agent_tool_lifecycle l on (l.workspace_id, l.namespace, l.name, l.version) = "
                        + "(v.workspace_id, v.namespace, v.name, v.version) where v.workspace_id = ? "
                        + "and v.namespace = ? and v.name = ? and v.version = ? and l.state = 'ACTIVE'",
                SUMMARIES, tenant, id.namespace(), id.name(), id.version());
        return hydrateSummaries(tenant, rows).stream().findFirst();
    }

    @Override
    public Page search(WorkspaceId workspace, Search request) {
        UUID tenant = workspace(workspace);
        Objects.requireNonNull(request, "request");
        StringBuilder sql = new StringBuilder("select " + SUMMARY_COLUMNS + " from agent_tool_version v "
                + "join agent_tool_lifecycle l on (l.workspace_id, l.namespace, l.name, l.version) = "
                + "(v.workspace_id, v.namespace, v.name, v.version) where v.workspace_id = ? and l.state = 'ACTIVE'");
        List<Object> arguments = new ArrayList<>();
        arguments.add(tenant);
        if (!request.query().isBlank()) {
            sql.append(" and lower(v.namespace || ':' || v.name || ' ' || v.summary) like ? escape '!'");
            arguments.add("%" + escapeLike(request.query().toLowerCase(java.util.Locale.ROOT)) + "%");
        }
        if (!request.types().isEmpty()) {
            sql.append(" and v.tool_type in (").append(placeholders(request.types().size())).append(')');
            request.types().stream().sorted().map(Enum::name).forEach(arguments::add);
        }
        for (String tag : request.tags().stream().sorted().toList()) {
            sql.append(" and exists (select 1 from agent_tool_tag t where (t.workspace_id, t.namespace, t.name, t.version) = ")
                    .append("(v.workspace_id, v.namespace, v.name, v.version) and t.tag = ?)");
            arguments.add(tag);
        }
        ToolId cursor = decodeCursor(request.cursor());
        if (cursor != null) {
            sql.append(" and (v.namespace, v.name, v.version) > (?, ?, ?)");
            arguments.add(cursor.namespace()); arguments.add(cursor.name()); arguments.add(cursor.version());
        }
        sql.append(" order by v.namespace, v.name, v.version fetch first ").append(request.limit() + 1)
                .append(" rows only");
        List<SummaryRow> rows = jdbc.query(sql.toString(), SUMMARIES, arguments.toArray());
        boolean more = rows.size() > request.limit();
        if (more) rows = new ArrayList<>(rows.subList(0, request.limit()));
        List<ToolSummary> items = hydrateSummaries(tenant, rows);
        String next = more ? encodeCursor(items.getLast().id()) : null;
        return new Page(items, next);
    }

    @Override
    public List<AgentTool> details(WorkspaceId workspace, Set<ToolId> ids) {
        UUID tenant = workspace(workspace);
        ids = Set.copyOf(Objects.requireNonNull(ids, "ids"));
        if (ids.isEmpty()) return List.of();
        if (ids.size() > 200) throw new IllegalArgumentException("at most 200 tool details may be loaded at once");
        List<ToolId> ordered = ids.stream().sorted().toList();
        StringBuilder sql = new StringBuilder("select " + DETAIL_COLUMNS + " from agent_tool_version v "
                + "join agent_tool_lifecycle l on (l.workspace_id, l.namespace, l.name, l.version) = "
                + "(v.workspace_id, v.namespace, v.name, v.version) where v.workspace_id = ? and l.state = 'ACTIVE' and (");
        List<Object> arguments = new ArrayList<>();
        arguments.add(tenant);
        for (int index = 0; index < ordered.size(); index++) {
            if (index > 0) sql.append(" or ");
            sql.append("(v.namespace = ? and v.name = ? and v.version = ?)");
            ToolId id = ordered.get(index);
            arguments.add(id.namespace()); arguments.add(id.name()); arguments.add(id.version());
        }
        sql.append(") order by v.namespace, v.name, v.version");
        return hydrateDetails(tenant, jdbc.query(sql.toString(), DETAILS, arguments.toArray()));
    }

    @Override
    public void insertTool(WorkspaceId workspace, AgentTool tool, Instant createdAt) {
        UUID tenant = workspace(workspace);
        OffsetDateTime timestamp = timestamp(createdAt);
        jdbc.update("insert into agent_tool_version(workspace_id, namespace, name, version, tool_type, summary, "
                        + "description, input_schema, schema_hash, effect_resource, effect_action, effect_risk, "
                        + "summary_tokens, detail_tokens, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tenant, tool.id().namespace(),
                tool.id().name(), tool.id().version(), tool.type().name(), tool.summary(), tool.description(),
                tool.inputSchema(), tool.schemaHash(), tool.effect().resource(), tool.effect().action(),
                tool.effect().risk().name(), tool.summaryTokens(), tool.detailTokens(), timestamp);
        jdbc.update("insert into agent_tool_lifecycle(workspace_id, namespace, name, version, state, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?)", tenant, tool.id().namespace(), tool.id().name(),
                tool.id().version(), tool.availability().name(), timestamp);
        tool.tags().stream().sorted().forEach(tag -> jdbc.update("insert into agent_tool_tag(workspace_id, namespace, "
                        + "name, version, tag) values (?, ?, ?, ?, ?)", tenant, tool.id().namespace(), tool.id().name(),
                tool.id().version(), tag));
        tool.compatibility().stream().sorted().forEach(requirement -> jdbc.update(
                "insert into agent_tool_compatibility(workspace_id, namespace, name, version, requirement) "
                        + "values (?, ?, ?, ?, ?)", tenant, tool.id().namespace(), tool.id().name(),
                tool.id().version(), requirement));
    }

    @Override
    public void transitionTool(WorkspaceId workspace, ToolId id, AgentTool.Availability state, Instant changedAt) {
        UUID tenant = workspace(workspace);
        int changed = jdbc.update("update agent_tool_lifecycle set state = ?, updated_at = ? where workspace_id = ? "
                        + "and namespace = ? and name = ? and version = ? and state <> 'DENIED' and state <> ?",
                state.name(), timestamp(changedAt), tenant, id.namespace(), id.name(), id.version(), state.name());
        if (changed != 1) transitionFailure(tenant, "agent_tool_lifecycle",
                "namespace = ? and name = ? and version = ?", "tool version", id.namespace(), id.name(), id.version());
    }

    @Override
    public void insertPack(WorkspaceId workspace, CapabilityPack pack, Instant createdAt) {
        UUID tenant = workspace(workspace);
        OffsetDateTime timestamp = timestamp(createdAt);
        jdbc.update("insert into capability_pack_version(workspace_id, pack_id, version, created_at) values (?, ?, ?, ?)",
                tenant, pack.id(), pack.version(), timestamp);
        jdbc.update("insert into capability_pack_lifecycle(workspace_id, pack_id, version, state, updated_at) "
                        + "values (?, ?, ?, ?, ?)", tenant, pack.id(), pack.version(), pack.state().name(), timestamp);
        for (int ordinal = 0; ordinal < pack.tools().size(); ordinal++) {
            ToolId tool = pack.tools().get(ordinal);
            jdbc.update("insert into capability_pack_tool(workspace_id, pack_id, pack_version, ordinal, "
                            + "tool_namespace, tool_name, tool_version) values (?, ?, ?, ?, ?, ?, ?)", tenant,
                    pack.id(), pack.version(), ordinal, tool.namespace(), tool.name(), tool.version());
        }
    }

    @Override
    public void transitionPack(WorkspaceId workspace, String id, String version, CapabilityPack.State state,
            Instant changedAt) {
        UUID tenant = workspace(workspace);
        int changed = jdbc.update("update capability_pack_lifecycle set state = ?, updated_at = ? where workspace_id = ? "
                + "and pack_id = ? and version = ? and state <> 'DENIED' and state <> ?", state.name(),
                timestamp(changedAt), tenant, id, version, state.name());
        if (changed != 1) transitionFailure(tenant, "capability_pack_lifecycle", "pack_id = ? and version = ?",
                "capability pack", id, version);
    }

    @Override
    public Optional<CapabilityPack> get(WorkspaceId workspace, String id, String version) {
        UUID tenant = workspace(workspace);
        List<String> states = jdbc.query("select l.state from capability_pack_version v join capability_pack_lifecycle l "
                        + "on (l.workspace_id, l.pack_id, l.version) = (v.workspace_id, v.pack_id, v.version) "
                        + "where v.workspace_id = ? and v.pack_id = ? and v.version = ? and l.state = 'ACTIVE'",
                (rs, row) -> rs.getString(1), tenant, id, version);
        if (states.isEmpty()) return Optional.empty();
        List<ToolId> tools = jdbc.query("select tool_namespace, tool_name, tool_version from capability_pack_tool "
                        + "where workspace_id = ? and pack_id = ? and pack_version = ? order by ordinal",
                (rs, row) -> new ToolId(rs.getString(1), rs.getString(2), rs.getString(3)), tenant, id, version);
        return Optional.of(new CapabilityPack(id, version, CapabilityPack.State.ACTIVE, tools));
    }

    @Override
    public List<CapabilityPack> activeVersions(WorkspaceId workspace, String id) {
        UUID tenant = workspace(workspace);
        List<String> versions = jdbc.query("select v.version from capability_pack_version v "
                        + "join capability_pack_lifecycle l on (l.workspace_id, l.pack_id, l.version) = "
                        + "(v.workspace_id, v.pack_id, v.version) where v.workspace_id = ? and v.pack_id = ? "
                        + "and l.state = 'ACTIVE' order by v.version", (rs, row) -> rs.getString(1), tenant, id);
        return versions.stream().map(version -> {
            List<ToolId> tools = jdbc.query("select tool_namespace, tool_name, tool_version from capability_pack_tool "
                            + "where workspace_id = ? and pack_id = ? and pack_version = ? order by ordinal",
                    (rs, row) -> new ToolId(rs.getString(1), rs.getString(2), rs.getString(3)), tenant, id, version);
            return new CapabilityPack(id, version, CapabilityPack.State.ACTIVE, tools);
        }).toList();
    }

    @Override
    public Snapshot snapshot(WorkspaceId workspace, List<com.dvarahq.agentworks.capability.PinnedCapabilityPack> pinned) {
        UUID tenant = workspace(workspace);
        pinned = List.copyOf(Objects.requireNonNull(pinned, "pinned"));
        if (pinned.isEmpty()) return new Snapshot(List.of(), List.of());

        List<CapabilityPack> packs = new ArrayList<>();
        LinkedHashSet<ToolId> toolIds = new LinkedHashSet<>();
        for (var evidence : pinned) {
            List<String> states = jdbc.query("select l.state from capability_pack_version v "
                            + "join capability_pack_lifecycle l on (l.workspace_id, l.pack_id, l.version) = "
                            + "(v.workspace_id, v.pack_id, v.version) where v.workspace_id = ? and v.pack_id = ? "
                            + "and v.version = ?", (rs, row) -> rs.getString(1), tenant, evidence.id(),
                    evidence.version());
            if (!states.isEmpty()) {
                List<ToolId> members = evidence.tools().stream()
                        .map(com.dvarahq.agentworks.capability.PinnedCapabilityPack.PinnedTool::id).toList();
                packs.add(new CapabilityPack(evidence.id(), evidence.version(), CapabilityPack.State.valueOf(
                        states.getFirst()), members));
                toolIds.addAll(members);
            }
        }
        if (toolIds.isEmpty()) return new Snapshot(packs, List.of());
        List<ToolId> ordered = toolIds.stream().sorted().toList();
        List<ToolSummary> tools = new ArrayList<>();
        // Keep each SQL statement bounded even when a release contains tools from several large packs.
        for (int start = 0; start < ordered.size(); start += 200) {
            List<ToolId> batch = ordered.subList(start, Math.min(start + 200, ordered.size()));
            StringBuilder sql = new StringBuilder("select " + SUMMARY_COLUMNS + " from agent_tool_version v "
                    + "join agent_tool_lifecycle l on (l.workspace_id, l.namespace, l.name, l.version) = "
                    + "(v.workspace_id, v.namespace, v.name, v.version) where v.workspace_id = ? and (");
            List<Object> arguments = new ArrayList<>();
            arguments.add(tenant);
            for (int index = 0; index < batch.size(); index++) {
                if (index > 0) sql.append(" or ");
                sql.append("(v.namespace = ? and v.name = ? and v.version = ?)");
                ToolId id = batch.get(index);
                arguments.add(id.namespace()); arguments.add(id.name()); arguments.add(id.version());
            }
            sql.append(") order by v.namespace, v.name, v.version");
            tools.addAll(hydrateSummaries(tenant, jdbc.query(sql.toString(), SUMMARIES, arguments.toArray())));
        }
        return new Snapshot(packs, tools);
    }

    @Override
    public List<AgentTool> details(WorkspaceId workspace, List<ToolId> selected) {
        UUID tenant = workspace(workspace);
        selected = List.copyOf(Objects.requireNonNull(selected, "selected"));
        if (selected.isEmpty()) return List.of();
        List<ToolId> ordered = selected.stream().distinct().sorted().toList();
        List<AgentTool> tools = new ArrayList<>();
        for (int start = 0; start < ordered.size(); start += 200) {
            List<ToolId> batch = ordered.subList(start, Math.min(start + 200, ordered.size()));
            StringBuilder sql = new StringBuilder("select " + DETAIL_COLUMNS + " from agent_tool_version v "
                    + "join agent_tool_lifecycle l on (l.workspace_id, l.namespace, l.name, l.version) = "
                    + "(v.workspace_id, v.namespace, v.name, v.version) where v.workspace_id = ? and (");
            List<Object> arguments = new ArrayList<>();
            arguments.add(tenant);
            appendToolPredicates(sql, arguments, batch);
            sql.append(") order by v.namespace, v.name, v.version");
            tools.addAll(hydrateDetails(tenant, jdbc.query(sql.toString(), DETAILS, arguments.toArray())));
        }
        return List.copyOf(tools);
    }

    private static void appendToolPredicates(StringBuilder sql, List<Object> arguments, List<ToolId> tools) {
        for (int index = 0; index < tools.size(); index++) {
            if (index > 0) sql.append(" or ");
            sql.append("(v.namespace = ? and v.name = ? and v.version = ?)");
            ToolId id = tools.get(index);
            arguments.add(id.namespace());
            arguments.add(id.name());
            arguments.add(id.version());
        }
    }

    private List<ToolSummary> hydrateSummaries(UUID workspace, List<SummaryRow> rows) {
        if (rows.isEmpty()) return List.of();
        List<ToolId> ids = rows.stream().map(SummaryRow::id).toList();
        Map<ToolId, Set<String>> tags = attributes(workspace, ids, "agent_tool_tag", "tag");
        Map<ToolId, Set<String>> compatibility = attributes(workspace, ids, "agent_tool_compatibility", "requirement");
        return rows.stream().map(row -> row.summary(tags.getOrDefault(row.id(), Set.of()),
                compatibility.getOrDefault(row.id(), Set.of()))).toList();
    }

    private List<AgentTool> hydrateDetails(UUID workspace, List<DetailRow> rows) {
        if (rows.isEmpty()) return List.of();
        List<ToolId> ids = rows.stream().map(DetailRow::id).toList();
        Map<ToolId, Set<String>> tags = attributes(workspace, ids, "agent_tool_tag", "tag");
        Map<ToolId, Set<String>> compatibility = attributes(workspace, ids, "agent_tool_compatibility", "requirement");
        return rows.stream().map(row -> row.tool(tags.getOrDefault(row.id(), Set.of()),
                compatibility.getOrDefault(row.id(), Set.of()))).toList();
    }

    private Map<ToolId, Set<String>> attributes(UUID workspace, List<ToolId> ids, String table, String column) {
        StringBuilder sql = new StringBuilder("select namespace, name, version, " + column + " from " + table
                + " where workspace_id = ? and (");
        List<Object> arguments = new ArrayList<>();
        arguments.add(workspace);
        for (int index = 0; index < ids.size(); index++) {
            if (index > 0) sql.append(" or ");
            sql.append("(namespace = ? and name = ? and version = ?)");
            ToolId id = ids.get(index);
            arguments.add(id.namespace()); arguments.add(id.name()); arguments.add(id.version());
        }
        sql.append(") order by namespace, name, version, ").append(column);
        Map<ToolId, Set<String>> result = new HashMap<>();
        jdbc.query(sql.toString(), rs -> {
            ToolId id = new ToolId(rs.getString(1), rs.getString(2), rs.getString(3));
            result.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(rs.getString(4));
        }, arguments.toArray());
        return result;
    }

    private static UUID workspace(WorkspaceId expected) {
        Objects.requireNonNull(expected, "workspace");
        UUID bound = TenantContext.requireWorkspace().value();
        if (!bound.equals(expected.value())) throw new IllegalArgumentException("workspace does not match tenant binding");
        return bound;
    }

    private void transitionFailure(UUID workspace, String table, String identityPredicate, String kind,
            Object... identity) {
        List<Object> arguments = new ArrayList<>();
        arguments.add(workspace);
        arguments.addAll(List.of(identity));
        Integer count = jdbc.queryForObject("select count(*) from " + table + " where workspace_id = ? and "
                + identityPredicate, Integer.class, arguments.toArray());
        if (count == null || count == 0) throw new CapabilityNotFoundException(kind);
        throw new CapabilityLifecycleConflictException(kind);
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "instant").truncatedTo(ChronoUnit.MICROS),
                ZoneOffset.UTC);
    }

    private static String placeholders(int count) { return String.join(", ", java.util.Collections.nCopies(count, "?")); }
    private static String escapeLike(String value) { return value.replace("!", "!!").replace("%", "!%").replace("_", "!_"); }
    private static String encodeCursor(ToolId id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(id.value().getBytes(StandardCharsets.UTF_8));
    }
    private static ToolId decodeCursor(String cursor) {
        if (cursor == null) return null;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1);
            if (parts.length != 3) throw new IllegalArgumentException("invalid tool cursor");
            return new ToolId(parts[0], parts[1], parts[2]);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid tool cursor", exception);
        }
    }

    private record SummaryRow(ToolId id, ToolType type, String summary, String schemaHash, ToolEffect effect, int summaryTokens,
            int detailTokens, AgentTool.Availability availability) {
        ToolSummary summary(Set<String> tags, Set<String> compatibility) {
            return new ToolSummary(id, type, summary, schemaHash, effect, tags, compatibility, summaryTokens, detailTokens,
                    availability);
        }
    }

    private record DetailRow(ToolId id, ToolType type, String summary, String description, String inputSchema,
            String schemaHash, ToolEffect effect, int summaryTokens, int detailTokens, AgentTool.Availability availability) {
        AgentTool tool(Set<String> tags, Set<String> compatibility) {
            return new AgentTool(id, type, summary, description, inputSchema, schemaHash, effect, tags, compatibility,
                    summaryTokens, detailTokens, availability);
        }
    }
}
