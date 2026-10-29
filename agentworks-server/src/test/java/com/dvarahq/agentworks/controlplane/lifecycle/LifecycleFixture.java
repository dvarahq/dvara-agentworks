package com.dvarahq.agentworks.controlplane.lifecycle;

import com.dvarahq.agentworks.controlplane.agent.AgentDefinitionService;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.DeploymentConfiguration;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EnvironmentView;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionCommand;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionPolicy;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionResult;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ReleaseView;
import com.dvarahq.agentworks.controlplane.deployment.DeploymentLifecycleService;
import com.dvarahq.agentworks.controlplane.deployment.EnvironmentService;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionService;
import com.dvarahq.agentworks.spec.AgentSpecCodec;
import com.dvarahq.agentworks.spec.SpecFormat;
import com.dvarahq.agentworks.storage.JdbcAgentDefinitionRepository;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import com.dvarahq.agentworks.storage.lifecycle.DatabaseClock;
import com.dvarahq.agentworks.storage.lifecycle.JdbcIdempotencyRepository;
import com.dvarahq.agentworks.storage.lifecycle.JdbcLifecycleRepository;
import com.dvarahq.agentworks.storage.lifecycle.JdbcRunAdmissionRepository;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.RunBlockReason;
import com.dvarahq.agentworks.domain.run.RunLease;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.run.RunLimits;
import com.dvarahq.agentworks.domain.run.RunPins;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.ReleaseManifestReader;
import com.dvarahq.agentworks.runplane.execution.RunEventCodec;
import com.dvarahq.agentworks.runplane.execution.RunLimitsPolicy;
import com.dvarahq.agentworks.storage.run.JdbcDurableRunStore;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

/**
 * Shared PostgreSQL lifecycle harness: one container with canonical and vendor migrations, real services, and
 * per-test workspaces so tests never share lifecycle rows.
 */
public final class LifecycleFixture {
    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    private static final java.util.Map<Class<?>, TestDatabase> POSTGRES_DATABASES = new java.util.HashMap<>();
    private static TestDatabase postgresTemplate;

    public final DataSource dataSource;
    public final JdbcTemplate jdbc;
    public final TransactionTemplate transactions;
    public final TenantTransactionTemplate tenants;
    public final JdbcDurableRunStore runStore;
    public final DurableRunEngine engine;
    /** The latest lease this fixture holds for each run it drove to ACTIVE. */
    private final java.util.Map<UUID, RunLease> leases = new java.util.concurrent.ConcurrentHashMap<>();
    public final JdbcLifecycleRepository lifecycle;
    public final JdbcIdempotencyRepository idempotencyRecords;
    public final JdbcRunAdmissionRepository runRecords;
    public final DatabaseClock clock;
    public final AgentDefinitionService agents;
    public final ReleaseService releases;
    public final EnvironmentService environments;
    public final DeploymentLifecycleService deployments;
    public final RunAdmissionService admissions;
    final LifecycleRetry retry;
    private final boolean capabilityCatalogPresent;
    public final AgentSpecCodec codec = AgentSpecCodec.create();
    /** The deterministic fake behind the {@code direct} gateway that fixture specifications name. */
    final com.dvarahq.agentworks.gateway.GatewayRegistry gateways = gateways("coding-model-2026-09-01");

    static com.dvarahq.agentworks.gateway.GatewayRegistry gateways(String codingPremium) {
        return com.dvarahq.agentworks.gateway.GatewayRegistry.of(java.util.List.of(
                new com.dvarahq.agentworks.gateway.ConfiguredGateway(
                        com.dvarahq.agentworks.gateway.fake.DeterministicFakeGatewayAdapter.builder("direct")
                                .model("coding-premium", codingPremium).build(),
                        com.dvarahq.agentworks.gateway.CapabilityPolicy.DEFAULT, null)));
    }

    /** Fixed test HMAC key; production keys come from configuration and are at least 32 bytes. */
    static final com.dvarahq.agentworks.storage.lifecycle.IdempotencyDigests TEST_DIGESTS = digests(java.util.Map.of(1, 'a'), 1);

    static com.dvarahq.agentworks.storage.lifecycle.IdempotencyDigests digests(java.util.Map<Integer, Character> fills,
            int active) {
        java.util.Map<Integer, byte[]> keys = new java.util.TreeMap<>();
        fills.forEach((version, fill) -> keys.put(version, String.valueOf(fill).repeat(32).getBytes(StandardCharsets.UTF_8)));
        return new com.dvarahq.agentworks.storage.lifecycle.IdempotencyDigests(keys, active);
    }

    public LifecycleFixture(DataSource dataSource, LifecycleRetry retry, Duration replayWindow) {
        this(dataSource, retry, replayWindow, TEST_DIGESTS);
    }

    LifecycleFixture(DataSource dataSource, LifecycleRetry retry, Duration replayWindow,
            com.dvarahq.agentworks.storage.lifecycle.IdempotencyDigests digests) {
        this(dataSource, retry, replayWindow, digests, RunLimits.UNBOUNDED);
    }

    public LifecycleFixture(DataSource dataSource, LifecycleRetry retry, Duration replayWindow,
            com.dvarahq.agentworks.storage.lifecycle.IdempotencyDigests digests, RunLimits runLimits) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.capabilityCatalogPresent = jdbc.queryForObject("select count(*) from information_schema.tables "
                + "where lower(table_name) = 'agent_tool_version'", Integer.class) > 0;
        this.transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        this.tenants = new TenantTransactionTemplate(transactions);
        this.runStore = new JdbcDurableRunStore(jdbc, transactions);
        this.engine = new DurableRunEngine(runStore, RunEventCodec.standard(), new ReleaseManifestReader(), retry,
                DurableRunEngine.compatibilityOf(RunEventCodec.standard()), EXECUTORS);
        this.lifecycle = new JdbcLifecycleRepository(jdbc);
        this.idempotencyRecords = new JdbcIdempotencyRepository(jdbc);
        this.runRecords = new JdbcRunAdmissionRepository(jdbc);
        this.clock = new DatabaseClock(jdbc);
        JdbcAgentDefinitionRepository definitions = new JdbcAgentDefinitionRepository(jdbc);
        this.agents = new AgentDefinitionService(tenants, definitions, codec, java.time.Clock.systemUTC());
        this.retry = retry;
        this.releases = releasesResolvingWith(gateways);
        this.environments = new EnvironmentService(tenants, lifecycle, clock, retry);
        this.deployments = new DeploymentLifecycleService(tenants, lifecycle, idempotencyRecords, clock, retry, replayWindow,
                digests);
        this.admissions = new RunAdmissionService(tenants, lifecycle, runRecords, clock, retry, digests, runStore, engine,
                RunLimitsPolicy.fixed(runLimits));
    }

    /** A release service over the same database whose model roles resolve through {@code registry}. */
    ReleaseService releasesResolvingWith(com.dvarahq.agentworks.gateway.ModelRoleResolver registry) {
        return releasesResolvingWith(registry, com.dvarahq.agentworks.capability.SkillRegistry.empty());
    }

    /** A release service over the same database with an explicit verified-skill registry. */
    ReleaseService releasesResolvingWith(com.dvarahq.agentworks.gateway.ModelRoleResolver registry,
            com.dvarahq.agentworks.capability.SkillRegistry skillRegistry) {
        com.dvarahq.agentworks.capability.CapabilityPackRegistry packs;
        com.dvarahq.agentworks.capability.ToolRegistry tools;
        if (capabilityCatalogPresent) {
            var capabilities = new com.dvarahq.agentworks.storage.capability.JdbcCapabilityRegistry(jdbc);
            packs = capabilities;
            tools = capabilities;
        } else {
            // Migration tests deliberately construct current services over pre-V24 schemas. Supply the historical
            // fixture capability in memory so those tests exercise the migration under test, not a future table.
            var tool = fixtureTool();
            packs = new com.dvarahq.agentworks.capability.CapabilityPackRegistry() {
                @Override
                public java.util.Optional<com.dvarahq.agentworks.capability.CapabilityPack> get(WorkspaceId workspace,
                        String id, String version) {
                    return activeVersions(workspace, id).stream().filter(pack -> pack.version().equals(version)).findFirst();
                }

                @Override
                public java.util.List<com.dvarahq.agentworks.capability.CapabilityPack> activeVersions(
                        WorkspaceId workspace, String id) {
                    return id.equals("software-development") ? java.util.List.of(fixturePack(tool)) : java.util.List.of();
                }
            };
            tools = new com.dvarahq.agentworks.capability.ToolRegistry() {
                @Override
                public java.util.Optional<com.dvarahq.agentworks.capability.ToolSummary> get(WorkspaceId workspace,
                        com.dvarahq.agentworks.capability.ToolId id) {
                    return java.util.Optional.empty();
                }

                @Override
                public Page search(WorkspaceId workspace, Search request) { return new Page(java.util.List.of(), null); }

                @Override
                public java.util.List<com.dvarahq.agentworks.capability.AgentTool> details(WorkspaceId workspace,
                        java.util.Set<com.dvarahq.agentworks.capability.ToolId> ids) {
                    return ids.contains(tool.id()) ? java.util.List.of(tool) : java.util.List.of();
                }
            };
        }
        return new ReleaseService(tenants, lifecycle, new JdbcAgentDefinitionRepository(jdbc), codec, clock, retry,
                registry, packs, tools, skillRegistry);
    }

    public static synchronized LifecycleFixture postgres() {
        return postgres(LifecycleRetry.standard(), Duration.ofHours(24));
    }

    public static synchronized LifecycleFixture postgres(LifecycleRetry retry, Duration replayWindow) {
        return postgres(retry, replayWindow, null, null);
    }

    /**
     * Creates the shared PostgreSQL fixture with bounded driver-level connect/read waits. Long-running qualification
     * tests use this overload so a host suspend or Docker network replacement becomes a typed connection failure
     * instead of leaving the test JVM blocked forever on an orphaned socket.
     */
    public static synchronized LifecycleFixture postgres(LifecycleRetry retry, Duration replayWindow,
            Duration connectTimeout, Duration socketTimeout) {
        validateTimeouts(connectTimeout, socketTimeout);
        return new LifecycleFixture(postgresDataSource(postgresDatabase(), connectTimeout, socketTimeout), retry,
                replayWindow);
    }

    /**
     * Reuses one PostgreSQL process and one migrated template while giving every test class an isolated database.
     * This permits class-level JUnit concurrency without sharing mutable rows or Flyway history.
     */
    public static synchronized TestDatabase postgresDatabase() {
        Class<?> testClass = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter(type -> type.getSimpleName().endsWith("Test"))
                .findFirst().orElseThrow(() -> new IllegalStateException("PostgreSQL fixture must be called by a test")));
        TestDatabase existing = POSTGRES_DATABASES.get(testClass);
        if (existing != null) return existing;
        if (postgresTemplate == null) {
            if (!POSTGRES.isRunning()) POSTGRES.start();
            String templateName = "test_template_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            createDatabase(templateName, null);
            postgresTemplate = database(templateName);
            migrate(postgresDataSource(postgresTemplate, null, null), true);
        }
        String stem = testClass.getSimpleName().replaceAll("[^A-Za-z0-9]", "").toLowerCase();
        if (stem.length() > 32) stem = stem.substring(0, 32);
        String databaseName = "test_" + stem + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        createDatabase(databaseName, postgresTemplate.databaseName());
        TestDatabase created = database(databaseName);
        POSTGRES_DATABASES.put(testClass, created);
        return created;
    }

    private static void createDatabase(String databaseName, String templateName) {
        String template = templateName == null ? "" : " template \"" + templateName + "\"";
        new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())).execute("create database \"" + databaseName + "\"" + template);
    }

    private static TestDatabase database(String databaseName) {
        String baseUrl = POSTGRES.getJdbcUrl();
        int query = baseUrl.indexOf('?');
        String suffix = query < 0 ? "" : baseUrl.substring(query);
        String path = query < 0 ? baseUrl : baseUrl.substring(0, query);
        String jdbcUrl = path.substring(0, path.lastIndexOf('/') + 1) + databaseName + suffix;
        return new TestDatabase(databaseName, jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void validateTimeouts(Duration connectTimeout, Duration socketTimeout) {
        if (connectTimeout == null && socketTimeout == null) return;
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                || socketTimeout == null || socketTimeout.isZero() || socketTimeout.isNegative()) {
            throw new IllegalArgumentException("PostgreSQL connect and socket timeouts must both be positive");
        }
    }

    private static DataSource postgresDataSource(TestDatabase database, Duration connectTimeout,
            Duration socketTimeout) {
        String url = database.jdbcUrl();
        if (connectTimeout != null) {
            String separator = url.contains("?") ? "&" : "?";
            url += separator + "connectTimeout=" + Math.max(1, connectTimeout.toSeconds())
                    + "&socketTimeout=" + Math.max(1, socketTimeout.toSeconds()) + "&tcpKeepAlive=true";
        }
        return new DriverManagerDataSource(url, database.username(), database.password());
    }

    public record TestDatabase(String databaseName, String jdbcUrl, String username, String password) {}

    public static void migrate(DataSource dataSource, boolean vendor) {
        var locations = vendor ? new String[]{"classpath:db/migration/common", "classpath:db/vendor/postgresql"}
                : new String[]{"classpath:db/migration/common"};
        // As the server's Flyway auto-configuration does (#206).
        Flyway.configure().dataSource(dataSource).locations(locations)
                .callbacks(new com.dvarahq.agentworks.storage.LockTimeoutRestoringCallback()).load().migrate();
    }

    /** A fresh organization and workspace per test keeps lifecycle rows isolated. */
    public WorkspaceId workspace() {
        UUID organization = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        jdbc.update("insert into organization(id, name) values (?, ?)", organization, "org-" + organization);
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", workspace, organization, "ws");
        WorkspaceId id = new WorkspaceId(workspace);
        if (!capabilityCatalogPresent) return id;
        seedFixtureCapabilities(id);
        return id;
    }

    /** Adds the fixture pack to a workspace created before the V24 capability catalog migration. */
    void seedFixtureCapabilities(WorkspaceId id) {
        if (!capabilityCatalogPresent) throw new IllegalStateException("capability catalog is not installed");
        var registry = new com.dvarahq.agentworks.storage.capability.JdbcCapabilityRegistry(jdbc);
        var tool = fixtureTool();
        tenants.executeWithoutResult(id, () -> {
            registry.insertTool(id, tool, java.time.Instant.parse("2026-01-01T00:00:00Z"));
            registry.insertPack(id, fixturePack(tool), java.time.Instant.parse("2026-01-01T00:00:00Z"));
        });
    }

    private static com.dvarahq.agentworks.capability.AgentTool fixtureTool() {
        String schema = "{\"type\":\"object\",\"properties\":{}}";
        return new com.dvarahq.agentworks.capability.AgentTool(
                new com.dvarahq.agentworks.capability.ToolId("core", "software-development", "1.0.0"),
                com.dvarahq.agentworks.capability.ToolType.JAVA, "Software development", "Fixture tool", schema,
                com.dvarahq.agentworks.capability.AgentTool.digest(schema),
                new com.dvarahq.agentworks.capability.ToolEffect("source-code", "write",
                        com.dvarahq.agentworks.domain.security.PermissionRisk.HIGH), java.util.Set.of("development"),
                java.util.Set.of(), 3, 8, com.dvarahq.agentworks.capability.AgentTool.Availability.ACTIVE);
    }

    private static com.dvarahq.agentworks.capability.CapabilityPack fixturePack(
            com.dvarahq.agentworks.capability.AgentTool tool) {
        return new com.dvarahq.agentworks.capability.CapabilityPack("software-development", "1.0.0",
                com.dvarahq.agentworks.capability.CapabilityPack.State.ACTIVE, java.util.List.of(tool.id()));
    }

    /**
     * A test executor type that checkpoints at every safe boundary and so declares itself resumable mid-exchange: an
     * operator may pause its active runs (#183). The Spring AI executor, which the default releases pin, may not.
     */
    public static final String CHECKPOINTING = "checkpointing-test";
    static final com.dvarahq.agentworks.runplane.execution.ExecutorCapabilities.Registry EXECUTORS =
            com.dvarahq.agentworks.runplane.execution.ExecutorCapabilities.Registry.of(java.util.Map.of(CHECKPOINTING,
                    new com.dvarahq.agentworks.runplane.execution.ExecutorCapabilities(true)));

    /** Publishes an AgentVersion and returns its identifier. */
    public UUID version(WorkspaceId workspace, String agent, String version) {
        return version(workspace, agent, version, "spring-ai");
    }

    /** Publishes an AgentVersion of {@code executorType} and returns its identifier. */
    public UUID version(WorkspaceId workspace, String agent, String version, String executorType) {
        var definition = agents.listDefinitions(workspace).stream().filter(item -> item.name().equals(agent)).findFirst()
                .orElseGet(() -> agents.createDefinition(workspace, agent, null));
        String yaml = """
                apiVersion: dvarahq.com/v1alpha1
                kind: Agent
                metadata: {name: %s, version: %s}
                spec:
                  executor: {type: %s}
                  models:
                    reasoning: {gateway: direct, model: coding-premium}
                  capabilityPacks: [software-development]
                """.formatted(agent, version, executorType);
        return agents.publishVersion(workspace, definition.id(), yaml.getBytes(StandardCharsets.UTF_8), SpecFormat.YAML)
                .version().id().value();
    }

    public ReleaseView release(WorkspaceId workspace, String agent, String version) {
        return release(workspace, agent, version, "spring-ai");
    }

    public ReleaseView release(WorkspaceId workspace, String agent, String version, String executorType) {
        return releases.createRelease(workspace, version(workspace, agent, version, executorType),
                agent + "-" + version);
    }

    public EnvironmentView environment(WorkspaceId workspace, String name) {
        return environments.create(workspace, name, null, PromotionPolicy.open());
    }

    public PromotionResult promote(WorkspaceId workspace, EnvironmentView environment, String deployment,
            ReleaseView release) {
        return deployments.promote(workspace, new PromotionCommand(environment.id(), deployment, release.id(),
                DeploymentConfiguration.shared(), null, null, "tester", "test promotion")).value();
    }

    /** Executes SQL as a least-privilege plane role in its own transaction. */
    public void asRole(String role, String sql, Object... arguments) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                UUID workspace = roleWorkspace(sql, arguments);
                if (workspace != null) {
                    statement.execute("select set_config('agentworks.workspace_id', '" + workspace + "', true)");
                }
                statement.execute("set local role " + role);
            }
            new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true))
                    .update(sql, arguments);
            connection.commit();
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Runs a counting query as a least-privilege plane role in its own read-only transaction. */
    public long countAsRole(String role, String sql, Object... arguments) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                UUID workspace = roleWorkspace(sql, arguments);
                if (workspace != null) {
                    statement.execute("select set_config('agentworks.workspace_id', '" + workspace + "', true)");
                }
                statement.execute("set local role " + role);
            }
            Long count = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,
                    true)).queryForObject(sql, Long.class, arguments);
            connection.rollback();
            return count == null ? 0 : count;
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Resolves the workspace named by direct-SQL role tests so they exercise the production RLS contract. */
    private UUID roleWorkspace(String sql, Object... arguments) {
        var candidates = new java.util.LinkedHashSet<UUID>();
        for (Object argument : arguments) {
            if (argument instanceof UUID id) candidates.add(id);
            if (argument instanceof WorkspaceId workspace) candidates.add(workspace.value());
        }
        var matcher = java.util.regex.Pattern.compile(
                "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matcher(sql);
        while (matcher.find()) candidates.add(UUID.fromString(matcher.group()));
        String lookup = "select workspace_id from ("
                + "select id as candidate, id as workspace_id from workspace union all "
                + "select id, workspace_id from agent_definition union all "
                + "select id, workspace_id from agent_version union all "
                + "select id, workspace_id from agent_release union all "
                + "select id, workspace_id from agent_environment union all "
                + "select id, workspace_id from agent_deployment union all "
                + "select id, workspace_id from lifecycle_decision union all "
                + "select id, workspace_id from deployment_revision union all "
                + "select id, workspace_id from runtime_cohort union all "
                + "select id, workspace_id from routing_generation union all "
                + "select id, workspace_id from control_plane_outbox union all "
                + "select id, workspace_id from agent_run union all "
                + "select id, workspace_id from cohort_work_item) scoped where candidate = ? fetch first 1 row only";
        for (UUID candidate : candidates) {
            var found = jdbc.query(lookup, (rs, row) -> rs.getObject(1, UUID.class), candidate);
            if (!found.isEmpty()) return found.getFirst();
        }
        return jdbc.query("select id from workspace order by created_at desc fetch first 1 row only",
                (rs, row) -> rs.getObject(1, UUID.class)).stream().findFirst().orElse(null);
    }

    /**
     * Writes as the table owner with triggers disabled, simulating a durable-work writer from a category that is
     * not yet fenced (wakeups, interactions, and operations arrive with #32 and #57). Only for tests.
     */
    public void bypassFence(String sql, Object... arguments) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("set local session_replication_role = replica");
            }
            new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true))
                    .update(sql, arguments);
            connection.commit();
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Inserts a CREATED continuation of {@code parent}, with its creation facts and RUN_CREATED event, directly and
     * outside the application and the fence: late work from a writer that skipped the fence. Its first resumable
     * write (queueing) then passes through the fence.
     */
    public UUID lateWork(com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RunRow parent) {
        UUID id = UUID.randomUUID();
        java.time.Instant now = clock.now();
        int manifestVersion = jdbc.queryForObject("select manifest_version from agent_run where id = ?", Integer.class,
                parent.id());
        String executorType = jdbc.queryForObject("select executor_type from agent_run where id = ?", String.class,
                parent.id());
        var pins = new RunPins(id, parent.workspaceId(), parent.definitionId(), parent.versionId(), parent.releaseId(),
                parent.deploymentId(), parent.environmentId(), parent.revisionId(), parent.cohortId(), "CONTINUATION",
                null, null, parent.id(), parent.rootRunId(), "CHILD", DurableRunEngine.newTraceId(), manifestVersion, RunLimits.UNBOUNDED,
                now);
        var created = engine.prepareCreation(pins, null, now).event();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("set local session_replication_role = replica");
            }
            var template = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
            template.update("insert into agent_run(workspace_id, id, definition_id, version_id, release_id, deployment_id, "
                    + "environment_id, revision_id, cohort_id, admission, parent_run_id, root_run_id, continuation_kind, "
                    + "lifecycle, created_at, trace_id, manifest_version, event_sequence, last_event_digest, executor_type) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CONTINUATION', ?, ?, 'CHILD', 'CREATED', ?, ?, ?, 1, ?, ?)",
                    parent.workspaceId(), id, parent.definitionId(), parent.versionId(), parent.releaseId(),
                    parent.deploymentId(), parent.environmentId(), parent.revisionId(), parent.cohortId(), parent.id(),
                    parent.rootRunId(), java.time.OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC), pins.traceId(),
                    manifestVersion, created.eventDigest(), executorType);
            template.update("insert into agent_run_event(workspace_id, run_id, sequence, id, event_type, schema_version, "
                    + "payload_json, payload_digest, event_digest, trace_id, span_id, release_id, deployment_id, revision_id, "
                    + "cohort_id, actor, lease_owner, lease_fence, created_at) values (?, ?, 1, ?, 'RUN_CREATED', 1, "
                    + "cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, null, 0, ?)", parent.workspaceId(), id, created.id(),
                    created.payloadJson(), created.payloadDigest(), created.eventDigest(), created.traceId(),
                    created.spanId(), created.releaseId(), created.deploymentId(), created.revisionId(), created.cohortId(),
                    created.actor(), java.time.OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC));
            connection.commit();
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(exception);
        }
        return id;
    }

    public long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    /**
     * Drives a run to {@code lifecycle} through the durable run engine along legal transitions, claiming it with a
     * fixture lease when it must become ACTIVE on the way. Every step appends its event and passes the fence.
     */
    public void setRunLifecycle(UUID runId, String lifecycle) {
        RunLifecycle target = RunLifecycle.valueOf(lifecycle);
        WorkspaceId workspace = new WorkspaceId(jdbc.queryForObject("select workspace_id from agent_run where id = ?",
                UUID.class, runId));
        if (target == RunLifecycle.CANCELLED) {
            engine.cancel(workspace, runId, "system:test", "test");
            return;
        }
        RunLifecycle current = engine.run(workspace, runId).lifecycle();
        if (current == target) return;
        if (current == RunLifecycle.CREATED) {
            engine.enqueue(workspace, runId, "system:test");
        } else if (current == RunLifecycle.BLOCKED || current == RunLifecycle.PAUSED) {
            engine.resume(workspace, runId, "system:test");
        } else if (current.terminal()) {
            engine.enqueue(workspace, runId, "system:test");
        }
        if (target == RunLifecycle.QUEUED) return;
        if (engine.run(workspace, runId).lifecycle() == RunLifecycle.QUEUED) claim(workspace, runId);
        if (target == RunLifecycle.ACTIVE) return;
        RunLease lease = leases.get(runId);
        switch (target) {
            case BLOCKED -> engine.block(lease, RunBlockReason.CHILD_RUN, null);
            case PAUSED -> engine.pause(lease, "test");
            case SUCCEEDED -> engine.complete(lease, null);
            case FAILED -> engine.fail(lease, "TEST_FAILURE", null);
            default -> throw new IllegalArgumentException(lifecycle);
        }
    }

    /** Claims exactly {@code runId}: every other run in its workspace is excluded from the claim scope. */
    public DurableRunEngine.ClaimedRun claim(WorkspaceId workspace, UUID runId) {
        var others = new java.util.HashSet<>(jdbc.queryForList("select id from agent_run where workspace_id = ? and id <> ?",
                UUID.class, workspace.value(), runId));
        var claimed = engine.claimNext("fixture-worker", ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS)
                .in(workspace).excluding(others), Duration.ofMinutes(5)).orElseThrow();
        leases.put(runId, claimed.lease());
        return claimed;
    }

    public RunLease lease(UUID runId) {
        return leases.get(runId);
    }
}
