package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.RunControl;
import com.dvarahq.agentworks.domain.run.RunLimits;
import com.dvarahq.agentworks.domain.run.RunPins;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.run.RunSummary;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;

import java.time.Duration;
import java.util.UUID;

/** Runtime-domain harness: the engine over the in-memory persistence port, with seeded runs. */
final class EngineHarness {
    static final Duration LEASE = Duration.ofSeconds(30);
    /**
     * A test executor type that checkpoints at every safe boundary and so declares itself resumable mid-exchange: an
     * operator may pause its active runs (#183). The Spring AI executor may not.
     */
    static final String CHECKPOINTING = "checkpointing-test";
    static final ExecutorCapabilities.Registry EXECUTORS = ExecutorCapabilities.Registry.of(
            java.util.Map.of(CHECKPOINTING, new ExecutorCapabilities(true)));

    final InMemoryDurableRunStore store;
    final DurableRunEngine engine;
    final WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());
    final UUID cohort = UUID.randomUUID();
    final UUID release = UUID.randomUUID();
    final UUID version = UUID.randomUUID();
    /** The manifest version new runs pin; tests that install a version 2 manifest set it to 2. */
    int manifestVersion = 1;

    EngineHarness() {
        this(EventSchemas.CURRENT);
    }

    EngineHarness(EventSchemas schemas) {
        this(schemas, "spring-ai", EXECUTORS);
    }

    /** A harness whose runs pin {@code executorType}, over an engine with {@code executors}' capabilities. */
    EngineHarness(String executorType, ExecutorCapabilities.Registry executors) {
        this(EventSchemas.CURRENT, executorType, executors);
    }

    private EngineHarness(EventSchemas schemas, String executorType, ExecutorCapabilities.Registry executors) {
        this.store = new InMemoryDurableRunStore();
        RunEventCodec codec = new RunEventCodec(schemas);
        this.engine = new DurableRunEngine(store, codec, new ReleaseManifestReader(), new LifecycleRetry(2),
                DurableRunEngine.compatibilityOf(codec), executors);
        store.cohort(cohort, "ROUTABLE");
        store.manifest(release, manifest(version, executorType));
    }

    /** Another tenant (workspace, cohort, release, and version) in the same store and engine as {@code shared}. */
    EngineHarness(EngineHarness shared) {
        this.store = shared.store;
        this.engine = shared.engine;
        store.cohort(cohort, "ROUTABLE");
        store.manifest(release, manifest(version));
    }

    static String manifest(UUID versionId) {
        return manifest(versionId, "spring-ai");
    }

    static String manifest(UUID versionId, String executorType) {
        return "{\"agent\":{\"definitionId\":\"" + UUID.randomUUID() + "\",\"definitionName\":\"agent\","
                + "\"specApiVersion\":\"dvarahq.com/v1alpha1\",\"specDigest\":\"sha256:" + "a".repeat(64) + "\","
                + "\"version\":\"1.0.0\",\"versionId\":\"" + versionId + "\"},\"capabilityPacks\":[\"software-development\"],"
                + "\"executor\":{\"requiredCapabilities\":[],\"type\":\"" + executorType + "\"},\"manifestVersion\":1,"
                + "\"models\":{\"reasoning\":{\"alias\":\"coding-premium\",\"gateway\":\"direct\","
                + "\"resolution\":\"UNRESOLVED_ALIAS\"}},\"sandbox\":{},\"skills\":[]}";
    }

    UUID run() {
        return run(RunLimits.UNBOUNDED, "{\"task\":\"test\"}");
    }

    /** Seeds a CREATED run with its RUN_CREATED event, as admission records it. */
    UUID run(RunLimits limits, Object input) {
        return run(limits, input, null);
    }

    /** As {@link #run(RunLimits, Object)}, recording {@code invoker} as the submitting principal. */
    UUID run(RunLimits limits, Object input, com.dvarahq.agentworks.domain.run.RunInvoker invoker) {
        UUID id = UUID.randomUUID();
        RunPins pins = new RunPins(id, workspace.value(), UUID.randomUUID(), version, release, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), cohort, "ROOT", UUID.randomUUID(), 0, null, id, null,
                DurableRunEngine.newTraceId(), manifestVersion, limits, store.now());
        var created = engine.prepareCreation(pins, input, store.now(), DurableRunEngine.ADMISSION_ACTOR, invoker).event();
        RunSummary summary = new RunSummary(com.dvarahq.agentworks.domain.run.RunLifecycle.CREATED, null, null, 1,
                created.eventDigest(), 0, 0, com.dvarahq.agentworks.domain.run.RunUsage.ZERO, null, null);
        store.seed(new RunRecord(pins, summary, RunControl.initial()), created);
        store.advance(Duration.ofMillis(1));
        return id;
    }

    ClaimScope scope() {
        return ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS).in(workspace);
    }

    /** The runs of this workspace that workers skip now: their durable claim quarantine (#181) is in force. */
    java.util.Set<UUID> quarantined() {
        return store.quarantines(workspace).stream().filter(quarantine -> quarantine.excludes(store.now()))
                .map(com.dvarahq.agentworks.domain.run.ClaimQuarantine::runId)
                .collect(java.util.stream.Collectors.toSet());
    }

    DurableRunEngine.ClaimedRun claim(String worker) {
        return engine.claimNext(worker, scope(), LEASE).orElseThrow();
    }

    /** Enqueues and claims a fresh run. */
    DurableRunEngine.ClaimedRun started(String worker) {
        UUID id = run();
        engine.enqueue(workspace, id, "system:test");
        return claim(worker);
    }
}
