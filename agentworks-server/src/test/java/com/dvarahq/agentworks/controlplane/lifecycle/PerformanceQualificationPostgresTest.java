package com.dvarahq.agentworks.controlplane.lifecycle;

import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.RunLimits;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionPolicy;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionPolicy.OverloadOutcome;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionPolicy.PlacementCapabilities;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionRejectedException;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionService;
import com.dvarahq.agentworks.runplane.execution.ReleaseManifestReader;
import com.dvarahq.agentworks.runplane.execution.RunEngineException;
import com.dvarahq.agentworks.runplane.execution.RunLimitsPolicy;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

import java.lang.management.ManagementFactory;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #63's reproducible PostgreSQL qualification workload. This is deliberately a correctness benchmark rather
 * than a marketing throughput number: it measures one declared reference workload while proving fenced ownership,
 * explicit shedding, database-authoritative recovery, and bounded long-running resource use.
 */
@Isolated("measures process-wide resource and latency budgets")
@Tag("qualification")
class PerformanceQualificationPostgresTest {
    private static final int WORKERS = Integer.getInteger("performance.workers",
            Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors())));
    private static final int BATCH = Integer.getInteger("performance.batch", WORKERS * 6);
    private static final int SOAK_ROOTS_PER_SECOND = Integer.getInteger("performance.admission.roots-per-second", 10_000);
    private static final int INJECT_STALE_LEASES = Integer.getInteger("performance.inject-stale-leases", 0);
    private static final int INJECT_ADMISSION_CONNECTION_FAILURES =
            Integer.getInteger("performance.inject-admission-connection-failures", 1);
    private static final int INJECT_WORKER_CONNECTION_FAILURES =
            Integer.getInteger("performance.inject-worker-connection-failures", 1);
    private static final int MAX_STALE_RETRIES = Integer.getInteger("performance.max-stale-retries", 32);
    private static final int MAX_CONNECTION_RECOVERY_ATTEMPTS =
            Integer.getInteger("performance.max-connection-recovery-attempts", 180);
    private static final Duration QUALIFICATION_LEASE =
            Duration.parse(System.getProperty("performance.lease", "PT1M"));
    private static final Duration CONNECTION_RECOVERY_DELAY =
            Duration.parse(System.getProperty("performance.connection-recovery-delay", "PT1S"));
    private static final Duration DATABASE_CONNECT_TIMEOUT =
            Duration.parse(System.getProperty("performance.database-connect-timeout", "PT5S"));
    private static final Duration DATABASE_SOCKET_TIMEOUT =
            Duration.parse(System.getProperty("performance.database-socket-timeout", "PT30S"));
    private static final Duration DURATION = Duration.parse(System.getProperty("performance.duration", "PT8S"));
    private static final Duration MAX_P95 = Duration.ofMillis(Long.getLong("performance.budget.p95-ms", 750L));
    private static final Duration MAX_RECOVERY = Duration.ofMillis(Long.getLong("performance.budget.recovery-ms", 750L));
    private static final long MAX_HEAP_GROWTH = Long.getLong("performance.budget.heap-growth-bytes", 256L * 1024 * 1024);
    private static final int MAX_THREAD_GROWTH = Integer.getInteger("performance.budget.thread-growth", 16);
    private static final int MAX_DATABASE_CONNECTIONS = Integer.getInteger("performance.budget.database-connections", 24);

    @Test
    void qualificationEnforcesScaleOverloadRecoveryAndSoakBudgets() throws Exception {
        Instant started = Instant.now();
        LifecycleFixture fixture = LifecycleFixture.postgres(LifecycleRetry.standard(), Duration.ofHours(24),
                DATABASE_CONNECT_TIMEOUT, DATABASE_SOCKET_TIMEOUT);
        long baselineHeap = usedHeap();
        int baselineThreads = ManagementFactory.getThreadMXBean().getThreadCount();
        SampleRecorder operationSamples = new SampleRecorder(reportDirectory().resolve("raw-samples.jsonl"));
        AtomicInteger submitted = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger shed = new AtomicInteger();
        AtomicInteger recovered = new AtomicInteger();
        AtomicInteger staleLeaseInjections = new AtomicInteger(INJECT_STALE_LEASES);
        AtomicInteger admissionConnectionFailureInjections =
                new AtomicInteger(INJECT_ADMISSION_CONNECTION_FAILURES);
        AtomicInteger workerConnectionFailureInjections = new AtomicInteger(INJECT_WORKER_CONNECTION_FAILURES);
        AtomicInteger connectionRecoveries = new AtomicInteger();
        AtomicInteger effectInvocations = new AtomicInteger();
        Map<String, Object> report = new LinkedHashMap<>();
        Throwable failure = null;

        try {
            var workload = workload(fixture);
            RunAdmissionService soakAdmissions = soakAdmissions(fixture);
            verifyExplicitShedding(fixture, workload, shed, operationSamples);
            verifyRecoveryFence(fixture, workload, recovered, operationSamples);

            long deadline = System.nanoTime() + DURATION.toNanos();
            do {
                List<UUID> batch = new ArrayList<>(BATCH);
                for (int index = 0; index < BATCH; index++) {
                    long before = System.nanoTime();
                    WorkspaceId workspace = index % 2 == 0 ? workload.left() : workload.right();
                    UUID deployment = index % 2 == 0 ? workload.leftDeployment() : workload.rightDeployment();
                    int sequence = submitted.get();
                    String idempotencyKey = "performance-soak-" + sequence;
                    UUID run = withConnectionRecovery("admit root " + sequence, connectionRecoveries, () -> {
                        var admitted = soakAdmissions.admitRoot(workspace, deployment, idempotencyKey,
                                Map.of("fixture", "deterministic", "sequence", sequence),
                                RunAdmissionService.Options.SUBMIT);
                        injectConnectionFailure(admissionConnectionFailureInjections,
                                "after committed root admission");
                        return admitted;
                    }).run().id();
                    submitted.incrementAndGet();
                    operationSamples.add(microsSince(before));
                    batch.add(run);
                }
                executeBatch(fixture, workload, batch, completed, recovered, effectInvocations,
                        staleLeaseInjections, workerConnectionFailureInjections, connectionRecoveries,
                        operationSamples);
            } while (System.nanoTime() < deadline);

            System.gc();
            Thread.sleep(100);
            long heapGrowth = Math.max(0, usedHeap() - baselineHeap);
            int threadGrowth = Math.max(0, ManagementFactory.getThreadMXBean().getThreadCount() - baselineThreads);
            int databaseConnections = fixture.jdbc.queryForObject("select count(*) from pg_stat_activity "
                    + "where datname = current_database()", Integer.class);
            long unfinished = fixture.count("select count(*) from agent_run where workspace_id in (?, ?) "
                            + "and lifecycle not in ('SUCCEEDED', 'FAILED', 'CANCELLED')",
                    workload.left().value(), workload.right().value());
            long duplicateTerminalEvents = fixture.count("select count(*) from (select run_id from agent_run_event "
                    + "where workspace_id in (?, ?) and event_type in ('RUN_SUCCEEDED','RUN_FAILED','RUN_CANCELLED') "
                    + "group by run_id having count(*) > 1) duplicates", workload.left().value(), workload.right().value());
            long committedEffects = fixture.count("select count(*) from agent_run_effect where workspace_id in (?, ?) "
                    + "and effect_key = 'qualification' and state = 'COMMITTED'",
                    workload.left().value(), workload.right().value());
            long p95Micros = operationSamples.percentileMicros(0.95);

            report.put("measurements", Map.ofEntries(
                    Map.entry("submitted", submitted.get()), Map.entry("completed", completed.get()),
                    Map.entry("shed", shed.get()), Map.entry("recovered", recovered.get()),
                    Map.entry("connectionRecoveries", connectionRecoveries.get()),
                    Map.entry("effectInvocations", effectInvocations.get()),
                    Map.entry("operationSamples", operationSamples.count()),
                    Map.entry("operationMeanMicros", operationSamples.meanMicros()),
                    Map.entry("operationStddevMicros", operationSamples.standardDeviationMicros()),
                    Map.entry("operationP95Micros", p95Micros), Map.entry("heapGrowthBytes", heapGrowth),
                    Map.entry("threadGrowth", threadGrowth), Map.entry("databaseConnections", databaseConnections),
                    Map.entry("committedEffects", committedEffects),
                    Map.entry("unfinishedRuns", unfinished),
                    Map.entry("duplicateTerminalEffects", duplicateTerminalEvents)));

            assertThat(committedEffects).isEqualTo(completed.get());
            assertThat(effectInvocations).hasValue(completed.get());
            assertThat(unfinished).isZero();
            assertThat(duplicateTerminalEvents).isZero();
            assertThat(Duration.ofNanos(TimeUnit.MICROSECONDS.toNanos(p95Micros))).isLessThanOrEqualTo(MAX_P95);
            assertThat(heapGrowth).isLessThanOrEqualTo(MAX_HEAP_GROWTH);
            assertThat(threadGrowth).isLessThanOrEqualTo(MAX_THREAD_GROWTH);
            assertThat(databaseConnections).isLessThanOrEqualTo(MAX_DATABASE_CONNECTIONS);
        } catch (Throwable caught) {
            failure = caught;
            throw caught;
        } finally {
            report.put("schemaVersion", 1);
            report.put("issue", 63);
            report.put("verdict", failure == null ? "PASS" : "FAIL");
            report.put("startedAt", started.toString());
            report.put("finishedAt", Instant.now().toString());
            report.put("commit", System.getProperty("performance.commit", "unknown"));
            report.put("dirty", Boolean.getBoolean("performance.dirty"));
            report.put("mode", System.getProperty("performance.mode", "quick"));
            report.put("workload", Map.ofEntries(
                    Map.entry("workers", WORKERS), Map.entry("batch", BATCH), Map.entry("warmupBatches", 0),
                    Map.entry("injectedStaleLeases", INJECT_STALE_LEASES),
                    Map.entry("injectedAdmissionConnectionFailures", INJECT_ADMISSION_CONNECTION_FAILURES),
                    Map.entry("injectedWorkerConnectionFailures", INJECT_WORKER_CONNECTION_FAILURES),
                    Map.entry("maximumStaleRetriesPerTask", MAX_STALE_RETRIES),
                    Map.entry("maximumConnectionRecoveryAttempts", MAX_CONNECTION_RECOVERY_ATTEMPTS),
                    Map.entry("connectionRecoveryDelay", CONNECTION_RECOVERY_DELAY.toString()),
                    Map.entry("qualificationLease", QUALIFICATION_LEASE.toString()),
                    Map.entry("databaseConnectTimeout", DATABASE_CONNECT_TIMEOUT.toString()),
                    Map.entry("databaseSocketTimeout", DATABASE_SOCKET_TIMEOUT.toString()),
                    Map.entry("admissionRootsPerSecond", SOAK_ROOTS_PER_SECOND),
                    Map.entry("minimumDuration", DURATION.toString()),
                    Map.entry("dependencyFixture", "deterministic-local"),
                    Map.entry("database", LifecycleFixture.POSTGRES.getDockerImageName())));
            report.put("budgets", Map.of("operationP95Millis", MAX_P95.toMillis(),
                    "recoveryMillis", MAX_RECOVERY.toMillis(), "heapGrowthBytes", MAX_HEAP_GROWTH,
                    "threadGrowth", MAX_THREAD_GROWTH, "databaseConnections", MAX_DATABASE_CONNECTIONS));
            report.put("environment", Map.of("java", System.getProperty("java.runtime.version"),
                    "os", System.getProperty("os.name") + " " + System.getProperty("os.version"),
                    "arch", System.getProperty("os.arch"), "processors", Runtime.getRuntime().availableProcessors(),
                    "maxHeapBytes", Runtime.getRuntime().maxMemory()));
            report.put("caveats", List.of(
                    "Results qualify the declared hardware and configuration; they are not universal capacity claims.",
                    "Quick mode detects regressions but does not replace the required 24-hour soak mode.",
                    "Model and tool latency is deterministic so runtime and database variance remain attributable."));
            if (failure != null) {
                report.put("failure", Map.of("type", failure.getClass().getName(),
                        "message", String.valueOf(failure.getMessage())));
                report.put("partialMeasurements", Map.of("submitted", submitted.get(),
                        "completed", completed.get(), "recovered", recovered.get(),
                        "effectInvocations", effectInvocations.get(),
                        "connectionRecoveries", connectionRecoveries.get(),
                        "operationSamples", operationSamples.count(),
                        "operationMeanMicros", operationSamples.meanMicros(),
                        "operationP95Micros", operationSamples.percentileMicros(0.95)));
            }
            operationSamples.close();
            writeReport(report);
        }
    }

    private static Workload workload(LifecycleFixture fixture) {
        WorkspaceId left = fixture.workspace();
        var leftEnvironment = fixture.environment(left, "perf-left");
        UUID leftDeployment = fixture.promote(left, leftEnvironment, "api",
                fixture.release(left, "perf-left", "1.0.0")).deploymentId();
        WorkspaceId right = fixture.workspace();
        var rightEnvironment = fixture.environment(right, "perf-right");
        UUID rightDeployment = fixture.promote(right, rightEnvironment, "api",
                fixture.release(right, "perf-right", "1.0.0")).deploymentId();
        return new Workload(left, leftDeployment, right, rightDeployment);
    }

    private static RunAdmissionService soakAdmissions(LifecycleFixture fixture) {
        RunAdmissionPolicy policy = new RunAdmissionPolicy("performance-soak-v1", 1_000, 100, 900, 90,
                SOAK_ROOTS_PER_SECOND, Duration.ofSeconds(1), 100,
                Map.of("SHARED", 1_000L, "ISOLATED", 100L, "DEDICATED", 100L), null, null,
                new PlacementCapabilities(Set.of(), Set.of(), Set.of(), Set.of()), OverloadOutcome.REJECTED);
        return new RunAdmissionService(fixture.tenants, fixture.lifecycle, fixture.runRecords,
                fixture.clock, fixture.retry, LifecycleFixture.TEST_DIGESTS, fixture.runStore, fixture.engine,
                RunLimitsPolicy.fixed(RunLimits.UNBOUNDED), policy);
    }

    private static void verifyExplicitShedding(LifecycleFixture fixture, Workload workload, AtomicInteger shed,
            SampleRecorder samples) {
        RunAdmissionPolicy policy = new RunAdmissionPolicy("performance-v1", 4, 1, 4, 4, 100,
                Duration.ofMinutes(1), 1, Map.of("SHARED", 1L, "ISOLATED", 1L, "DEDICATED", 1L),
                null, null, new PlacementCapabilities(Set.of(), Set.of(), Set.of(), Set.of()),
                OverloadOutcome.DEFERRED);
        RunAdmissionService bounded = new RunAdmissionService(fixture.tenants, fixture.lifecycle, fixture.runRecords,
                fixture.clock, fixture.retry, LifecycleFixture.TEST_DIGESTS, fixture.runStore, fixture.engine,
                RunLimitsPolicy.fixed(RunLimits.UNBOUNDED), policy);
        var accepted = bounded.admitRoot(workload.left(), workload.leftDeployment(), "capacity-holder", Map.of(),
                RunAdmissionService.Options.SUBMIT).run();
        long before = System.nanoTime();
        assertThatThrownBy(() -> bounded.admitRoot(workload.left(), workload.leftDeployment(), "shed", Map.of(),
                RunAdmissionService.Options.SUBMIT)).isInstanceOfSatisfying(RunAdmissionRejectedException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("ADMISSION_DEFERRED"));
        samples.add(microsSince(before));
        shed.incrementAndGet();
        assertThat(fixture.jdbc.queryForObject("select 1", Integer.class)).isOne();
        fixture.setRunLifecycle(accepted.id(), "SUCCEEDED");
    }

    private static void verifyRecoveryFence(LifecycleFixture fixture, Workload workload, AtomicInteger recovered,
            SampleRecorder samples) {
        UUID run = fixture.admissions.admitRoot(workload.right(), workload.rightDeployment(), "recover", Map.of(),
                RunAdmissionService.Options.SUBMIT).run().id();
        var stale = fixture.claim(workload.right(), run);
        fixture.bypassFence("update agent_run set lease_until = clock_timestamp() - interval '1 millisecond' where id = ?",
                run);
        long before = System.nanoTime();
        var replacement = fixture.engine.claimNext("recovery-worker",
                ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS).in(workload.right()), QUALIFICATION_LEASE)
                .orElseThrow();
        samples.add(microsSince(before));
        assertThat(replacement.recovered()).isTrue();
        assertThat(Duration.ofNanos(TimeUnit.MICROSECONDS.toNanos(microsSince(before))))
                .isLessThanOrEqualTo(MAX_RECOVERY);
        assertThatThrownBy(() -> fixture.engine.complete(stale.lease(), Map.of()))
                .isInstanceOf(RunEngineException.class);
        fixture.engine.complete(replacement.lease(), Map.of("recovered", true));
        recovered.incrementAndGet();
    }

    private static void executeBatch(LifecycleFixture fixture, Workload workload, List<UUID> batch,
            AtomicInteger completed, AtomicInteger recovered, AtomicInteger effectInvocations,
            AtomicInteger staleLeaseInjections, AtomicInteger workerConnectionFailureInjections,
            AtomicInteger connectionRecoveries, SampleRecorder samples) throws Exception {
        Set<UUID> expectedRuns = Set.copyOf(batch);
        Set<UUID> leftExpectedRuns = ConcurrentHashMap.newKeySet();
        Set<UUID> rightExpectedRuns = ConcurrentHashMap.newKeySet();
        for (int index = 0; index < batch.size(); index++) {
            (index % 2 == 0 ? leftExpectedRuns : rightExpectedRuns).add(batch.get(index));
        }
        Set<UUID> batchCompleted = ConcurrentHashMap.newKeySet();
        Set<String> invokedEffectKeys = ConcurrentHashMap.newKeySet();
        try (var workers = Executors.newFixedThreadPool(WORKERS)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < WORKERS; worker++) {
                String owner = "qualification-worker-" + worker;
                WorkspaceId workspace = worker % 2 == 0 ? workload.left() : workload.right();
                Set<UUID> workspaceExpectedRuns = worker % 2 == 0 ? leftExpectedRuns : rightExpectedRuns;
                futures.add(workers.submit(() -> {
                    long before = System.nanoTime();
                    int staleRetries = 0;
                    int consecutiveConnectionFailures = 0;
                    UUID uncertainRun = null;
                    while (!batchCompleted.containsAll(workspaceExpectedRuns)) {
                        try {
                            if (uncertainRun != null && terminal(fixture, uncertainRun)) {
                                recordCompletion(uncertainRun, batchCompleted, completed, samples, before);
                                uncertainRun = null;
                                consecutiveConnectionFailures = 0;
                                before = System.nanoTime();
                                continue;
                            }
                            var candidate = fixture.engine.claimNext(owner,
                                    ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS).in(workspace),
                                    QUALIFICATION_LEASE);
                            if (candidate.isEmpty()) {
                                pauseForConnectionRecovery();
                                continue;
                            }
                            var claimed = candidate.orElseThrow();
                            UUID id = claimed.run().id();
                            uncertainRun = id;
                            consecutiveConnectionFailures = 0;
                            if (!expectedRuns.contains(id)) {
                                throw new AssertionError("qualification worker claimed an unrelated run " + id);
                            }
                            if (claimed.recovered()) recovered.incrementAndGet();
                            if (staleLeaseInjections.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
                                fixture.bypassFence("update agent_run set lease_until = clock_timestamp() "
                                        + "- interval '1 millisecond' where id = ?", id);
                            }
                            var effect = fixture.engine.executeEffect(claimed.lease(), "qualification",
                                    Map.of("run", id), (externalKey, request) -> {
                                        if (invokedEffectKeys.add(externalKey)) effectInvocations.incrementAndGet();
                                        return tools.jackson.databind.node.JsonNodeFactory.instance
                                                .stringNode(externalKey);
                                    });
                            injectWorkerConnectionFailure(fixture, workerConnectionFailureInjections, id);
                            if (!claimed.recovered()) assertThat(effect.replayed()).isFalse();
                            var afterEffect = fixture.engine.heartbeat(effect.lease(), QUALIFICATION_LEASE);
                            var replay = fixture.engine.executeEffect(afterEffect, "qualification", Map.of("run", id),
                                    (externalKey, request) -> {
                                        throw new AssertionError("committed effect was invoked twice");
                                    });
                            assertThat(replay.replayed()).isTrue();
                            var afterReplay = fixture.engine.heartbeat(replay.lease(), QUALIFICATION_LEASE);
                            fixture.engine.complete(afterReplay, Map.of("worker", owner));
                            recordCompletion(id, batchCompleted, completed, samples, before);
                            uncertainRun = null;
                            staleRetries = 0;
                            before = System.nanoTime();
                        } catch (RunEngineException failure) {
                            if (!failure.code().equals("STALE_LEASE") || ++staleRetries > MAX_STALE_RETRIES) {
                                throw failure;
                            }
                        } catch (RuntimeException failure) {
                            if (!connectionFailure(failure)) throw failure;
                            if (++consecutiveConnectionFailures > MAX_CONNECTION_RECOVERY_ATTEMPTS) {
                                throw new AssertionError("database connection did not recover after "
                                        + MAX_CONNECTION_RECOVERY_ATTEMPTS + " attempts", failure);
                            }
                            connectionRecoveries.incrementAndGet();
                            pauseForConnectionRecovery();
                        }
                    }
                }));
            }
            workers.shutdown();
            assertThat(workers.awaitTermination(10, TimeUnit.MINUTES)).isTrue();
            for (Future<?> future : futures) future.get();
        }
        assertThat(batchCompleted).containsExactlyInAnyOrderElementsOf(expectedRuns);
    }

    private static void recordCompletion(UUID run, Set<UUID> batchCompleted, AtomicInteger completed,
            SampleRecorder samples, long before) {
        if (batchCompleted.add(run)) {
            completed.incrementAndGet();
            samples.add(microsSince(before));
        }
    }

    private static boolean terminal(LifecycleFixture fixture, UUID run) {
        return fixture.count("select count(*) from agent_run where id = ? "
                + "and lifecycle in ('SUCCEEDED', 'FAILED', 'CANCELLED')", run) == 1;
    }

    private static <T> T withConnectionRecovery(String operation, AtomicInteger connectionRecoveries,
            Supplier<T> work) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_CONNECTION_RECOVERY_ATTEMPTS; attempt++) {
            try {
                return work.get();
            } catch (RuntimeException failure) {
                if (!connectionFailure(failure)) throw failure;
                last = failure;
                connectionRecoveries.incrementAndGet();
                if (attempt < MAX_CONNECTION_RECOVERY_ATTEMPTS) pauseForConnectionRecovery();
            }
        }
        throw new AssertionError(operation + " did not recover after " + MAX_CONNECTION_RECOVERY_ATTEMPTS
                + " attempts", last);
    }

    private static void injectConnectionFailure(AtomicInteger remaining, String boundary) {
        if (remaining.getAndUpdate(value -> Math.max(0, value - 1)) <= 0) return;
        throw new DataAccessResourceFailureException("injected connection loss " + boundary,
                new SQLException("injected connection loss", "08006"));
    }

    private static void injectWorkerConnectionFailure(LifecycleFixture fixture, AtomicInteger remaining, UUID run) {
        if (remaining.getAndUpdate(value -> Math.max(0, value - 1)) <= 0) return;
        // The injected loss models a host pause longer than the declared lease without making a quick gate sleep for
        // a full production lease. Recovery still uses the ordinary PostgreSQL claim path and a higher fence.
        fixture.bypassFence("update agent_run set lease_until = clock_timestamp() "
                + "- interval '1 millisecond' where id = ?", run);
        throw new DataAccessResourceFailureException("injected connection loss after the idempotent effect boundary",
                new SQLException("injected connection loss", "08006"));
    }

    private static boolean connectionFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException) return true;
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("08")) return true;
        }
        return false;
    }

    private static void pauseForConnectionRecovery() {
        try {
            Thread.sleep(CONNECTION_RECOVERY_DELAY.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for database recovery", interrupted);
        }
    }

    private static long microsSince(long before) {
        return TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - before);
    }

    private static long usedHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static Path reportDirectory() {
        return Path.of(System.getProperty("performance.report.dir", "target/performance-evidence"));
    }

    private static void writeReport(Map<String, Object> report) {
        try {
            Path directory = reportDirectory();
            Files.createDirectories(directory);
            JsonMapper mapper = JsonMapper.builder().build();
            mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("report.json").toFile(), report);
        } catch (Exception error) {
            throw new IllegalStateException("cannot write performance evidence", error);
        }
    }

    /** Streams every sample to disk while retaining only a fixed 60,001-bucket millisecond histogram in heap. */
    private static final class SampleRecorder implements AutoCloseable {
        private final long[] milliseconds = new long[60_001];
        private final BufferedWriter writer;
        private long sequence;
        private double sumMicros;
        private double sumSquaresMicros;

        private SampleRecorder(Path path) throws Exception {
            Files.createDirectories(path.getParent());
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        }

        synchronized void add(long micros) {
            milliseconds[(int) Math.min(milliseconds.length - 1, Math.max(0, micros / 1_000))]++;
            sumMicros += micros;
            sumSquaresMicros += (double) micros * micros;
            try {
                writer.write("{\"sequence\":" + ++sequence + ",\"operationMicros\":" + micros + "}\n");
            } catch (Exception error) {
                throw new IllegalStateException("cannot write raw performance sample", error);
            }
        }

        synchronized long percentileMicros(double percentile) {
            long target = Math.max(1, (long) Math.ceil(sequence * percentile));
            long seen = 0;
            for (int bucket = 0; bucket < milliseconds.length; bucket++) {
                seen += milliseconds[bucket];
                if (seen >= target) return bucket * 1_000L;
            }
            throw new IllegalStateException("performance histogram is empty");
        }

        synchronized long count() {
            return sequence;
        }

        synchronized double meanMicros() {
            return sequence == 0 ? 0 : sumMicros / sequence;
        }

        synchronized double standardDeviationMicros() {
            if (sequence < 2) return 0;
            double mean = meanMicros();
            return Math.sqrt(Math.max(0, sumSquaresMicros / sequence - mean * mean));
        }

        @Override
        public synchronized void close() {
            try {
                writer.close();
            } catch (Exception error) {
                throw new IllegalStateException("cannot close raw performance samples", error);
            }
        }
    }

    private record Workload(WorkspaceId left, UUID leftDeployment, WorkspaceId right, UUID rightDeployment) { }
}
