package com.dvarahq.agentworks.controlplane.lifecycle;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.CohortCommand;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionService.ContinuationKind;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fresh-schema and upgrade migrations on PostgreSQL, and H2 compatibility for local development. */
@Execution(ExecutionMode.CONCURRENT)
@Tag("qualification")
class LifecycleMigrationTest {

    @Test
    @ResourceLock("postgres-migration-a")
    void upgradeFromSchemaV2PreservesPublishedVersions() {
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            DataSource source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration/common", "classpath:db/vendor/postgresql")
                    .target(MigrationVersion.fromVersion("2.1")).load().migrate();
            var v2 = new LifecycleFixture(source, LifecycleRetry.standard(), Duration.ofHours(24));
            WorkspaceId workspace = v2.workspace();
            UUID version = v2.version(workspace, "agent", "1.0.0");

            var flyway = Flyway.configure().dataSource(source)
                    .locations("classpath:db/migration/common", "classpath:db/vendor/postgresql").load();
            var result = flyway.migrate();

            // Every migration after V1, V2, and V2.1 runs, and none remains pending.
            assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(8)
                    .isEqualTo(flyway.info().applied().length - 3);
            assertThat(flyway.info().pending()).isEmpty();
            var upgraded = new LifecycleFixture(source, LifecycleRetry.standard(), Duration.ofHours(24));
            upgraded.seedFixtureCapabilities(workspace);
            var release = upgraded.releases.createRelease(workspace, version, "agent-1.0.0");
            assertThat(release.versionId()).isEqualTo(version);
            assertThat(upgraded.count("select count(*) from agent_version where id = ?", version)).isOne();
        }
    }

    @Test
    @ResourceLock("postgres-migration-b")
    void aNonSuperuserMigrationUserInstallsTheFenceWithCreatedOrPreCreatedRoles() {
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword()));
            // A migration user with CREATEROLE creates the plane and fence roles itself.
            admin.execute("create role creator login password 'creator' createrole");
            admin.execute("create database creator_db owner creator");
            // Without CREATEROLE, the installer pre-creates the roles and grants them with ADMIN OPTION.
            admin.execute("create role installer login password 'installer'");
            admin.execute("create database installer_db owner installer");
            DataSource created = new DriverManagerDataSource(postgres.getJdbcUrl().replace("/test", "/creator_db"),
                    "creator", "creator");
            LifecycleFixture.migrate(created, true);
            assertTheFenceWorksUnderItsOwner(created);
            // The membership V19_1 granted itself to hand the auditor role its function is revoked again (PR #199
            // review); the creator keeps only the non-inheriting ADMIN grant PostgreSQL gives a role's creator.
            assertThat(admin.queryForObject("select pg_has_role('creator', 'agentworks_run_control_auditor', 'SET')",
                    Boolean.class)).isFalse();

            admin.execute("grant agentworks_control_plane, agentworks_run_plane, agentworks_fence_owner, "
                    + "agentworks_run_control_auditor to installer with admin option");
            DataSource preCreated = new DriverManagerDataSource(postgres.getJdbcUrl().replace("/test", "/installer_db"),
                    "installer", "installer");
            LifecycleFixture.migrate(preCreated, true);
            assertTheFenceWorksUnderItsOwner(preCreated);
            assertThat(new JdbcTemplate(preCreated).queryForObject(
                    "select has_schema_privilege('agentworks_fence_owner', 'public', 'CREATE')", Boolean.class)).isFalse();
        }
    }

    // PR #199 review, medium 1 and low 4: V19_1's new role is an upgrade prerequisite for a migration user without
    // CREATEROLE. Without it the migration stops before changing anything, with a typed error naming the role and the
    // exact statements to run; once a database administrator runs them, the retried migration succeeds. The trigger
    // replacement waits at most lock_timeout for agent_run, so a busy table fails the upgrade fast, and it is retried.
    @Test
    @ResourceLock("postgres-migration-a")
    void aMissingAuditorRoleOrABusyRunTableFailsTheUpgradeFastWithARetryableTypedError() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword()));
            admin.execute("create role installer login password 'installer'");
            admin.execute("create database installer_db owner installer");
            for (String role : java.util.List.of("agentworks_control_plane", "agentworks_run_plane",
                    "agentworks_fence_owner")) {
                admin.execute("create role " + role + " nologin");
            }
            admin.execute("grant agentworks_control_plane, agentworks_run_plane, agentworks_fence_owner to installer "
                    + "with admin option");
            String url = postgres.getJdbcUrl().replace("/test", "/installer_db");
            DataSource installer = new DriverManagerDataSource(url, "installer", "installer");
            var flyway = Flyway.configure().dataSource(installer)
                    .locations("classpath:db/migration/common", "classpath:db/vendor/postgresql");

            assertThatThrownBy(() -> flyway.load().migrate()).hasStackTraceContaining("agentworks:ROLE_REQUIRED")
                    .hasStackTraceContaining("CREATE ROLE agentworks_run_control_auditor NOLOGIN")
                    .hasStackTraceContaining("GRANT agentworks_run_control_auditor TO installer WITH ADMIN OPTION");
            var installerJdbc = new JdbcTemplate(installer);
            assertThat(installerJdbc.queryForObject("select count(*) from flyway_schema_history where version = '19.1'",
                    Integer.class)).isZero();
            assertThat(installerJdbc.queryForObject("select proowner::regrole::text from pg_proc where proname = "
                    + "'agentworks_append_run_control_audit'", String.class)).isEqualTo("agentworks_fence_owner");

            admin.execute("create role agentworks_run_control_auditor nologin");
            admin.execute("grant agentworks_run_control_auditor to installer with admin option");
            // A transaction that has read agent_run holds the table: the trigger replacement gives up after its
            // lock_timeout instead of stalling, and the migration rolls back whole.
            try (var reader = java.sql.DriverManager.getConnection(url, "installer", "installer")) {
                reader.setAutoCommit(false);
                try (var statement = reader.createStatement()) {
                    statement.execute("select count(*) from agent_run");
                }
                long started = System.nanoTime();
                assertThatThrownBy(() -> flyway.load().migrate()).hasStackTraceContaining("lock timeout");
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(60));
                reader.rollback();
            }
            assertThat(installerJdbc.queryForObject("select count(*) from flyway_schema_history where version = '19.1'",
                    Integer.class)).isZero();

            // #201 low 5: each attempt waits briefly and is retried inside the migration, so a table that is busy only
            // while the first attempt waits no longer fails the upgrade. The reader lets go once that attempt's wait
            // has ended (its lock request is gone while the migration still runs).
            try (var reader = java.sql.DriverManager.getConnection(url, "installer", "installer")) {
                reader.setAutoCommit(false);
                try (var statement = reader.createStatement()) {
                    statement.execute("select count(*) from agent_run");
                }
                var migrated = java.util.concurrent.CompletableFuture.runAsync(() -> flyway.load().migrate(),
                        java.util.concurrent.Executors.newThreadPerTaskExecutor(Thread.ofPlatform().factory()));
                String waiting = "select count(*) from pg_locks where relation = 'agent_run'::regclass "
                        + "and mode = 'AccessExclusiveLock' and not granted";
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (installerJdbc.queryForObject(waiting, Integer.class) == 0) {
                    assertThat(System.nanoTime()).as("the first attempt waits").isLessThan(deadline);
                    Thread.onSpinWait();
                }
                while (installerJdbc.queryForObject(waiting, Integer.class) > 0) {
                    assertThat(System.nanoTime()).as("the first attempt times out").isLessThan(deadline);
                    Thread.onSpinWait();
                }
                assertThat(migrated).as("the upgrade outlives a timed-out attempt").isNotDone();
                reader.rollback();
                migrated.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertThat(installerJdbc.queryForObject("select proowner::regrole::text from pg_proc where proname = "
                    + "'agentworks_append_run_control_audit'", String.class)).isEqualTo("agentworks_run_control_auditor");
        }
    }

    // #201 medium 2: V19_1's own revoke leaves the cluster-wide auditor role without the migration user's membership.
    // On PostgreSQL 15 and older a CREATEROLE migration user may grant itself any non-superuser role, so V19_1's
    // pre-flight accepts it (the user's decision: V19_1 was edited in place, unreleased), and a second database in the
    // same cluster, and the same database after flyway clean, migrate. PostgreSQL 16 and later keep the creator's ADMIN
    // grant. On PostgreSQL 15 the second database failed V19_1 with ROLE_REQUIRED before the fix.
    @Test
    @ResourceLock("postgres-migration-b")
    void aCreateRoleMigrationUserMigratesASecondDatabaseAndACleanedOneInTheSameCluster() {
        for (String image : java.util.List.of("postgres:15.2-alpine", "postgres:17-alpine")) {
            migrateTwoDatabasesAndACleanedOne(image);
        }
    }

    private static void migrateTwoDatabasesAndACleanedOne(String image) {
        try (var postgres = new PostgreSQLContainer(image)) {
            postgres.start();
            var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword()));
            admin.execute("create role creator login password 'creator' createrole");
            admin.execute("create database first_db owner creator");
            admin.execute("create database second_db owner creator");
            DataSource first = new DriverManagerDataSource(postgres.getJdbcUrl().replace("/test", "/first_db"),
                    "creator", "creator");
            DataSource second = new DriverManagerDataSource(postgres.getJdbcUrl().replace("/test", "/second_db"),
                    "creator", "creator");
            LifecycleFixture.migrate(first, true);
            // V19_1 leaves no membership it granted itself (#201 review, low 3): on PostgreSQL 15 and older none at
            // all; on 16 and later only the creator's non-inheriting, non-SET ADMIN grant.
            boolean legacy = admin.queryForObject("select current_setting('server_version_num')::integer < 160000",
                    Boolean.class);
            assertThat(admin.queryForObject("select pg_has_role('creator', 'agentworks_run_control_auditor', "
                    + (legacy ? "'MEMBER'" : "'SET'") + ")", Boolean.class)).isFalse();
            assertThat(admin.queryForObject("select count(*) from pg_auth_members m where m.roleid = "
                    + "'agentworks_run_control_auditor'::regrole and m.member = 'creator'::regrole and m.admin_option",
                    Integer.class)).isEqualTo(legacy ? 0 : 1);
            LifecycleFixture.migrate(second, true);
            assertTheFenceWorksUnderItsOwner(second);

            Flyway.configure().dataSource(first).cleanDisabled(false)
                    .locations("classpath:db/migration/common", "classpath:db/vendor/postgresql").load().clean();
            LifecycleFixture.migrate(first, true);
            assertTheFenceWorksUnderItsOwner(first);
        }
    }

    private static void assertTheFenceWorksUnderItsOwner(DataSource source) {
        var fixture = new LifecycleFixture(source, LifecycleRetry.standard(), Duration.ofHours(24));
        WorkspaceId workspace = fixture.workspace();
        var environment = fixture.environment(workspace, "prod");
        var promoted = fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "1.0.0"));
        var run = fixture.admissions.admitRoot(workspace, promoted.deploymentId(), null, "{}").run();
        assertThat(run.cohortId()).isEqualTo(promoted.cohortId());
        assertThat(fixture.jdbc.queryForObject("select proowner::regrole::text from pg_proc where proname = "
                + "'agentworks_fence_run'", String.class)).isEqualTo("agentworks_fence_owner");
        var next = fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "2.0.0"));
        assertThatThrownBy(() -> fixture.jdbc.update("update agent_run set cohort_id = ? where workspace_id = ? and id = ?",
                next.cohortId(), workspace.value(), run.id())).hasStackTraceContaining("IMMUTABLE_PIN");
        // #194 review: the Run Plane audit guard (V17_1) must not restrict the owner, which serves both planes when no
        // plane URLs are configured, whether or not it inherits the plane roles it created.
        UUID organization = fixture.jdbc.queryForObject("select organization_id from workspace where id = ?", UUID.class,
                workspace.value());
        fixture.jdbc.update("insert into auth_audit_event(id, occurred_at, event_type, outcome, organization_id, "
                + "workspace_id, target_type, target_id, detail) values (?, now(), 'LOGIN_SUCCEEDED', 'SUCCEEDED', ?, ?, "
                + "'USER', ?, 'PASSWORD')", UUID.randomUUID(), organization, workspace.value(), UUID.randomUUID());
        // #198: the run-control audit function is owned by its own NOLOGIN role under either kind of owner.
        assertThat(fixture.jdbc.queryForObject("select proowner::regrole::text from pg_proc where proname = "
                + "'agentworks_append_run_control_audit'", String.class)).isEqualTo("agentworks_run_control_auditor");
    }

    @Test
    @ResourceLock("postgres-migration-a")
    void schemaV1PlaceholderRowsFailTheMigrationInsteadOfReceivingInventedHistory() {
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            DataSource source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration/common", "classpath:db/vendor/postgresql")
                    .target(MigrationVersion.fromVersion("2.1")).load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID organization = UUID.randomUUID();
            UUID workspace = UUID.randomUUID();
            jdbc.update("insert into organization(id, name) values (?, 'o')", organization);
            jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, 'w')", workspace, organization);
            jdbc.update("insert into agent_environment(workspace_id, id, name) values (?, ?, 'placeholder')",
                    workspace, UUID.randomUUID());

            assertThatThrownBy(() -> Flyway.configure().dataSource(source)
                    .locations("classpath:db/migration/common", "classpath:db/vendor/postgresql").load().migrate())
                    .isInstanceOf(FlywayException.class);
        }
    }

    // Issue #34, review 4, items 1 and 2: V9_1 backfills the executor type of runs admitted before V9 from their pinned
    // release manifests, through the run guards, and guards it from then on.
    @Test
    @ResourceLock("postgres-migration-b")
    void theExecutorTypeMigrationBackfillsExistingRunsAndThenGuardsTheColumn() {
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            DataSource source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration/common", "classpath:db/vendor/postgresql")
                    .target(MigrationVersion.fromVersion("9")).load().migrate();
            var before = new LifecycleFixture(source, LifecycleRetry.standard(), Duration.ofHours(24));
            WorkspaceId workspace = before.workspace();
            var environment = before.environment(workspace, "prod");
            var release = before.release(workspace, "agent", "1.0.0");
            var promoted = before.promote(workspace, environment, "api", release);
            UUID[] runs = insertPreExecutorRuns(before, workspace, environment.id(), release, promoted, true);
            UUID root = runs[0];
            UUID child = runs[1];
            // As the rows were before V9: the column exists but nothing recorded the executor type.
            before.bypassFence("update agent_run set executor_type = null where workspace_id = ?", workspace.value());
            assertThat(before.count("select count(*) from agent_run where executor_type is null")).isEqualTo(2);

            var flyway = Flyway.configure().dataSource(source)
                    .locations("classpath:db/migration/common", "classpath:db/vendor/postgresql").load();
            flyway.migrate();

            assertThat(flyway.info().pending()).isEmpty();
            var after = new LifecycleFixture(source, LifecycleRetry.standard(), Duration.ofHours(24));
            assertThat(after.jdbc.queryForList("select executor_type from agent_run where id in (?, ?)", String.class,
                    root, child)).containsExactly("spring-ai", "spring-ai");
            // The run guards were re-enabled: the backfilled value is now an immutable creation fact.
            assertThat(after.count("select count(*) from pg_trigger where tgrelid = 'agent_run'::regclass "
                    + "and not tgisinternal and tgenabled = 'D'")).isZero();
            assertThatThrownBy(() -> after.jdbc.update("update agent_run set executor_type = 'remote-http' where id = ?",
                    root)).hasStackTraceContaining("IMMUTABLE_PIN");
            assertThat(after.jdbc.queryForObject("select proowner::regrole::text from pg_proc where proname = "
                    + "'agentworks_fence_run_executor_type'", String.class)).isEqualTo("agentworks_fence_owner");
        }
    }

    @Test
    void theExecutorTypeMigrationBackfillsExistingRunsOnH2() {
        DataSource h2 = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        Flyway.configure().dataSource(h2).locations("classpath:db/migration/common", "classpath:db/vendor/h2")
                .target(MigrationVersion.fromVersion("9")).load().migrate();
        var before = new LifecycleFixture(h2, LifecycleRetry.standard(), Duration.ofHours(24));
        WorkspaceId workspace = before.workspace();
        var environment = before.environment(workspace, "local");
        var release = before.release(workspace, "agent", "1.0.0");
        var promoted = before.promote(workspace, environment, "api", release);
        UUID run = insertPreExecutorRuns(before, workspace, environment.id(), release, promoted, false)[0];
        before.jdbc.update("update agent_run set executor_type = null");

        Flyway.configure().dataSource(h2).locations("classpath:db/migration/common", "classpath:db/vendor/h2").load()
                .migrate();

        assertThat(before.jdbc.queryForObject("select executor_type from agent_run where id = ?", String.class, run))
                .isEqualTo("spring-ai");
    }

    /** Inserts the pre-V9 run shape without asking current admission code to operate against a historical schema. */
    private static UUID[] insertPreExecutorRuns(LifecycleFixture fixture, WorkspaceId workspace, UUID environmentId,
            LifecycleModel.ReleaseView release, LifecycleModel.PromotionResult promoted, boolean continuation) {
        UUID root = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String columns = "workspace_id, id, version_id, release_id, deployment_id, environment_id, created_at, "
                + "definition_id, revision_id, cohort_id, admission, routing_generation_id, routing_target_ordinal, "
                + "parent_run_id, root_run_id, continuation_kind, lifecycle, trace_id, manifest_version, updated_at";
        String rootSql = "insert into agent_run(" + columns + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ROOT', "
                + "?, 0, null, ?, null, 'CREATED', null, null, ?)";
        Object[] rootArguments = {workspace.value(), root, release.versionId(), release.id(), promoted.deploymentId(),
                environmentId, now, release.definitionId(), promoted.revisionId(), promoted.cohortId(),
                promoted.routingGenerationId(), root, now};
        if (continuation) fixture.bypassFence(rootSql, rootArguments);
        else fixture.jdbc.update(rootSql, rootArguments);
        if (continuation) {
            fixture.bypassFence("insert into agent_run(" + columns + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                    + "'CONTINUATION', null, null, ?, ?, 'CHILD', 'CREATED', null, null, ?)", workspace.value(), child,
                    release.versionId(), release.id(), promoted.deploymentId(), environmentId, now,
                    release.definitionId(), promoted.revisionId(), promoted.cohortId(), root, root, now);
        }
        return continuation ? new UUID[]{root, child} : new UUID[]{root};
    }

    @Test
    void theLifecycleRunsOnH2ForLocalDevelopmentWithoutPostgresOnlyFences() {
        DataSource h2 = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        LifecycleFixture.migrate(h2, false);
        var fixture = new LifecycleFixture(h2, LifecycleRetry.standard(), Duration.ofHours(24));
        WorkspaceId workspace = fixture.workspace();
        var environment = fixture.environment(workspace, "local");

        var r1 = fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "1.0.0"));
        var run = fixture.admissions.admitRoot(workspace, r1.deploymentId(), "k", "{}").run();
        var r2 = fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "2.0.0"));
        var child = fixture.admissions.admitContinuation(workspace, run.id(), ContinuationKind.CHILD).run();
        fixture.jdbc.update("update agent_run set lifecycle = 'SUCCEEDED' where cohort_id = ?", r1.cohortId());
        var retired = fixture.deployments.retire(workspace, new CohortCommand(r1.cohortId(),
                fixture.deployments.cohort(workspace, r1.cohortId()).transitionVersion(), "retire", "t", null)).value();

        assertThat(fixture.admissions.admitRoot(workspace, r1.deploymentId(), "k", "{}").run().id()).isEqualTo(run.id());
        assertThat(child.cohortId()).isEqualTo(r1.cohortId());
        assertThat(retired.outcome()).isEqualTo("RETIRED");
        assertThat(fixture.admissions.admitRoot(workspace, r1.deploymentId(), null, "{}").run().revisionId())
                .isEqualTo(r2.revisionId());
    }
}
