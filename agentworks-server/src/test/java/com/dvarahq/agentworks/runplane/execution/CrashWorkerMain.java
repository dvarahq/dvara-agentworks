package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import com.dvarahq.agentworks.storage.run.JdbcDurableRunStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

/**
 * Child-JVM worker for the kill/restart test. It claims exactly the given run, checkpoints,
 * starts a modeled external effect, applies it in the external system, and then terminates the process with
 * {@code Runtime.halt} before the effect's outcome is committed and without releasing its lease.
 */
public final class CrashWorkerMain {
    public static final int EXIT = 91;

    private CrashWorkerMain() {}

    public static void main(String[] args) {
        var dataSource = new DriverManagerDataSource(args[0], args[1], args[2]);
        WorkspaceId workspace = new WorkspaceId(UUID.fromString(args[3]));
        UUID runId = UUID.fromString(args[4]);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var store = new JdbcDurableRunStore(jdbc, new TransactionTemplate(new JdbcTransactionManager(dataSource)));
        var engine = new DurableRunEngine(store, RunEventCodec.standard(), new ReleaseManifestReader(),
                LifecycleRetry.standard());
        var others = new HashSet<>(jdbc.queryForList("select id from agent_run where workspace_id = ? and id <> ?",
                UUID.class, workspace.value(), runId));
        ClaimScope scope = ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS).in(workspace).excluding(others);
        try (var worker = new RunWorker(engine, context -> {
            context.checkpoint(Map.of("step", 1, "input", context.input()));
            context.effect("charge-42", Map.of("amount", 42), (externalKey, request) -> {
                jdbc.update("insert into external_charge(external_key, amount) values (?, ?) "
                        + "on conflict (external_key) do nothing", externalKey, request.path("amount").intValue());
                Runtime.getRuntime().halt(EXIT);
                return JsonNodeFactory.instance.nullNode();
            });
            return RunOutcome.completed("unreachable");
        }, "crash-worker", scope, Duration.ofMinutes(1))) {
            worker.pollOnce();
        }
        System.exit(1);
    }
}
