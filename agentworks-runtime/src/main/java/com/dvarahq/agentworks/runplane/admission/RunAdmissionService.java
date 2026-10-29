package com.dvarahq.agentworks.runplane.admission;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.runplane.RunPlaneComponent;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import com.dvarahq.agentworks.storage.lifecycle.DatabaseClock;
import com.dvarahq.agentworks.storage.lifecycle.IdempotencyDigests;
import com.dvarahq.agentworks.storage.lifecycle.Digests;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleReads;
import com.dvarahq.agentworks.storage.lifecycle.JdbcRunAdmissionRepository;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.CohortRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.DeploymentRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RevisionRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RoutingGenerationRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RoutingTargetRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RunRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.ReleaseRow;
import com.dvarahq.agentworks.domain.run.DurableRunStore;
import com.dvarahq.agentworks.domain.run.RunAuthorityException;
import com.dvarahq.agentworks.domain.run.RunLimits;
import com.dvarahq.agentworks.domain.run.RunPins;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.PreparedCreation;
import com.dvarahq.agentworks.runplane.execution.ReleaseManifestReader;
import com.dvarahq.agentworks.runplane.execution.ReleaseManifestReader.RunManifest;
import com.dvarahq.agentworks.runplane.execution.RunEngineException;
import com.dvarahq.agentworks.runplane.execution.RunEventCodec;
import com.dvarahq.agentworks.runplane.execution.RunLimitsPolicy;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Run Plane admission (ADR-027). An independent root resolves scoped idempotency first, then the deployment's
 * current routing generation, and permanently pins the version, release, deployment, environment, revision,
 * cohort, routing generation, and target. A continuation is created only from a non-terminal parent run and
 * inherits the parent's pins; it never consults routing, so external input cannot use it to reach a draining
 * cohort. On PostgreSQL the cohort-fence trigger re-validates both under the same locks as promotion and
 * retirement; a stale route is a serialization failure that retries against the new route.
 *
 * <p>Issue #32: the creation transaction also pins the trace identity, the release-manifest version (read and
 * validated by {@link ReleaseManifestReader}, so a node never admits work it cannot load), and the run's limits,
 * and appends the {@code RUN_CREATED} event carrying every pin and the canonical input. A root submitted for
 * execution is queued in the same transaction.
 *
 * <p>Run records are never deleted, so a root's idempotent result stays reconstructible for as long as the run
 * exists: a matching retry always returns the original run and pins.
 */
public final class RunAdmissionService implements RunPlaneComponent {
    public enum ContinuationKind { CHILD, RETRY, COMPENSATION }

    public record AdmittedRun(RunRow run, boolean replayed) {}

    /** Admission options: queue the run for execution in the creation transaction, and an optional trace id. */
    /**
     * @param actor the authenticated actor recorded on the run's creation and queueing events (issue #74), or
     *              {@code null} for internal admission ({@code system:admission})
     * @param invoker the authenticated principal, recorded on {@code RUN_CREATED} so a worker can invoke models on its
     *                behalf (issue #34), or {@code null} for internal admission; it is not part of the idempotency
     *                fingerprint, which already carries {@code actor}
     */
    public record CausalParent(UUID parentRunId, UUID rootRunId) {
        public CausalParent { Objects.requireNonNull(parentRunId); Objects.requireNonNull(rootRunId); }
    }

    public record Options(boolean queue, String traceId, String actor,
            com.dvarahq.agentworks.domain.run.RunInvoker invoker, RunLimits internalLimitOverride,
            CausalParent causalParent) {
        public static final String SYSTEM_ACTOR = DurableRunEngine.ADMISSION_ACTOR;
        public static final Options CREATE_ONLY = new Options(false, null);
        public static final Options SUBMIT = new Options(true, null);

        public Options {
            actor = actor == null ? SYSTEM_ACTOR : RunEventCodec.identity(actor, "actor");
            if ((internalLimitOverride != null || causalParent != null) && !"system:child-run".equals(actor)) {
                throw new IllegalArgumentException("only child-run authority may narrow a run's limits");
            }
        }

        public Options(boolean queue, String traceId) {
            this(queue, traceId, null, null, null, null);
        }

        public Options(boolean queue, String traceId, String actor) {
            this(queue, traceId, actor, null, null, null);
        }

        public Options(boolean queue, String traceId, String actor,
                com.dvarahq.agentworks.domain.run.RunInvoker invoker) {
            this(queue, traceId, actor, invoker, null, null);
        }

        public Options(boolean queue, String traceId, String actor,
                com.dvarahq.agentworks.domain.run.RunInvoker invoker, RunLimits limits) {
            this(queue, traceId, actor, invoker, limits, null);
        }
    }

    private static final String NAMESPACE = "run.admit";
    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private static final Pattern FENCE_CODE = Pattern.compile("agentworks:([A-Z_]+)(?: ([A-Z_]+))?");

    private final TenantTransactionTemplate tenants;
    private final LifecycleReads lifecycle;
    private final JdbcRunAdmissionRepository runs;
    private final DatabaseClock clock;
    private final LifecycleRetry retry;
    private final IdempotencyDigests digests;
    private final DurableRunStore history;
    private final DurableRunEngine engine;
    private final RunLimitsPolicy limits;
    private final RunAdmissionPolicy policy;
    private final RunAdmissionObserver observer;
    private final ReleaseManifestReader manifests = new ReleaseManifestReader();

    /**
     * {@code digests} may be null, in which case requests carrying an idempotency key are refused. {@code history}
     * must share the transaction manager of {@code tenants}, so the run row and its first events commit together.
     */
    public RunAdmissionService(TenantTransactionTemplate tenants, LifecycleReads lifecycle,
            JdbcRunAdmissionRepository runs, DatabaseClock clock, LifecycleRetry retry, IdempotencyDigests digests,
            DurableRunStore history, DurableRunEngine engine, RunLimitsPolicy limits) {
        this(tenants, lifecycle, runs, clock, retry, digests, history, engine, limits, RunAdmissionPolicy.defaults(),
                RunAdmissionObserver.NONE);
    }

    public RunAdmissionService(TenantTransactionTemplate tenants, LifecycleReads lifecycle,
            JdbcRunAdmissionRepository runs, DatabaseClock clock, LifecycleRetry retry, IdempotencyDigests digests,
            DurableRunStore history, DurableRunEngine engine, RunLimitsPolicy limits, RunAdmissionPolicy policy) {
        this(tenants, lifecycle, runs, clock, retry, digests, history, engine, limits, policy,
                RunAdmissionObserver.NONE);
    }

    public RunAdmissionService(TenantTransactionTemplate tenants, LifecycleReads lifecycle,
            JdbcRunAdmissionRepository runs, DatabaseClock clock, LifecycleRetry retry, IdempotencyDigests digests,
            DurableRunStore history, DurableRunEngine engine, RunLimitsPolicy limits, RunAdmissionPolicy policy,
            RunAdmissionObserver observer) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.digests = digests;
        this.history = Objects.requireNonNull(history, "history");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /**
     * Admits an independent root run through current routing. The request fingerprint is computed here from the
     * canonical admission request (deployment and JSON input with sorted object keys) and stored only as a versioned
     * HMAC digest; a key reused for a different request conflicts before any route is selected.
     */
    public AdmittedRun admitRoot(WorkspaceId workspace, UUID deploymentId, String idempotencyKey, Object input) {
        return admitRoot(workspace, deploymentId, idempotencyKey, input, Options.CREATE_ONLY);
    }

    /** As {@link #admitRoot(WorkspaceId, UUID, String, Object)}, queueing the new run when requested. */
    public AdmittedRun admitRoot(WorkspaceId workspace, UUID deploymentId, String idempotencyKey, Object input,
            Options options) {
        Objects.requireNonNull(deploymentId, "deploymentId");
        Objects.requireNonNull(options, "options");
        String traceId = options.traceId() == null ? DurableRunEngine.newTraceId() : trace(options.traceId());
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 255)) {
            throw new RunAdmissionRejectedException("INVALID_IDEMPOTENCY_KEY", "idempotency keys are 1 to 255 characters");
        }
        if (idempotencyKey != null && digests == null) {
            throw new RunAdmissionRejectedException("IDEMPOTENCY_UNAVAILABLE",
                    "idempotency keys require configured HMAC keys (agentworks.idempotency.hmac-keys)");
        }
        String scope = workspace.value().toString();
        String canonical = canonicalRequest(deploymentId, input, options.actor());
        IdempotencyDigests.Digest key = idempotencyKey == null ? null : digests.key(scope, NAMESPACE, idempotencyKey);
        List<String> candidates = idempotencyKey == null ? List.of() : digests.keyCandidates(scope, NAMESPACE,
                idempotencyKey).stream().map(IdempotencyDigests.Digest::hex).toList();
        String fingerprint = key == null ? null
                : digests.fingerprint(key.version(), scope, NAMESPACE, canonical).orElseThrow();
        AdmissionAttempt attempt = fencedAttempt(() -> retry.execute("run.admit", () -> tenants.execute(workspace, () -> {
            DeploymentRow deployment = lifecycle.findDeployment(deploymentId)
                    .orElseThrow(() -> new RunAdmissionRejectedException("NOT_FOUND", "deployment not found"));
            if (key != null) {
                runs.lockKey(deploymentId, digests.lockScope(NAMESPACE, idempotencyKey));
                var existing = runs.findByIdempotencyKey(deploymentId, candidates);
                if (existing.isPresent()) {
                    RunRow original = existing.get();
                    String recorded = digests.fingerprint(original.idempotencyDigestVersion(), scope, NAMESPACE, canonical)
                            .orElseThrow(() -> new RunAdmissionRejectedException("IDEMPOTENCY_KEY_VERSION_RETIRED",
                                    "the HMAC key version that recorded this request is no longer retained"));
                    if (!recorded.equals(original.requestFingerprint())) {
                        throw new RunAdmissionRejectedException("IDEMPOTENCY_KEY_REUSED",
                                "the idempotency key was already used for a different request");
                    }
                    return AdmissionAttempt.accepted(new AdmittedRun(original, true), "REPLAYED", null);
                }
                if (runs.holdsUnretainedVersion(deploymentId, digests.retainedVersions())) {
                    throw new RunAdmissionRejectedException("IDEMPOTENCY_KEY_VERSION_RETIRED",
                            "runs admitted under a retired HMAC key version remain, so a replay cannot be ruled out");
                }
            }
            if (deployment.currentRoutingGenerationId() == null) {
                throw new RunAdmissionRejectedException("NO_ROUTE", "the deployment has no routing generation");
            }
            RoutingGenerationRow routing = lifecycle.findRoutingGeneration(deployment.currentRoutingGenerationId()).orElseThrow();
            List<RoutingTargetRow> targets = lifecycle.routingTargets(routing.id());
            // Core ordinary routing is exactly one full-weight target; other policies need explicit authorization.
            if (!"SINGLE_TARGET".equals(routing.policy()) || targets.size() != 1 || targets.getFirst().weight() != 100) {
                throw new RunAdmissionRejectedException("UNSUPPORTED_ROUTING_POLICY",
                        "Core admission requires one full-weight routing target");
            }
            RoutingTargetRow target = targets.getFirst();
            CohortRow cohort = lifecycle.findCohort(target.cohortId()).orElseThrow();
            if (!"ROUTABLE".equals(cohort.state())) {
                // Each read sees the latest commit, so a promotion committing between the deployment and cohort
                // reads shows the old route's cohort as draining. That is a stale route, retried like the fence's
                // stale-generation failure, not a refusal.
                UUID current = lifecycle.findDeployment(deploymentId).orElseThrow().currentRoutingGenerationId();
                if (!routing.id().equals(current)) {
                    throw new OptimisticLockingFailureException("the deployment was re-routed during admission");
                }
                throw new RunAdmissionRejectedException("COHORT_NOT_ADMITTING", "the routed cohort is " + cohort.state());
            }
            RevisionRow revision = lifecycle.findRevision(target.revisionId()).orElseThrow();
            ReleaseRow release = lifecycle.findRelease(revision.releaseId()).orElseThrow();
            RunManifest manifest = manifests.read(workspace, release.id(), release.manifestJson());
            RunLimits releaseLimits = Objects.requireNonNull(limits.limitsFor(release), "limits");
            RunLimits runLimits = options.internalLimitOverride() == null ? releaseLimits
                    : narrower(options.internalLimitOverride(), releaseLimits);
            boolean requiresSandbox = manifest.sandboxConfiguration().isPresent();
            Instant decisionTime = clock.now();
            runs.lockCapacity(deploymentId);
            var snapshot = runs.admissionSnapshot(deploymentId, revision.placement(),
                    decisionTime.minus(policy.rateWindow()));
            var decision = policy.evaluate(snapshot, new RunAdmissionPolicy.Request(true, options.queue(),
                    requiresSandbox, revision.placement(), runLimits, placementConstraints(revision)));
            String requestDigest = Digests.prefixed(canonical);
            if (!decision.accepted()) {
                runs.insertDecision(new JdbcRunAdmissionRepository.AdmissionDecision(UUID.randomUUID(), deploymentId,
                        null, options.causalParent() == null ? "ROOT" : "CHILD", decision.outcome(), decision.code(), revision.placement(), policy.version(),
                        requestDigest, snapshot, decisionTime));
                return AdmissionAttempt.rejected(externalCode(decision), decision.code() + ": " + decision.detail(),
                        decision.outcome(), revision.placement());
            }
            com.dvarahq.agentworks.domain.run.RunRecord causal = options.causalParent() == null ? null
                    : history.inTransaction(workspace, tx -> tx.lockRun(options.causalParent().parentRunId()))
                        .orElseThrow(() -> new RunAdmissionRejectedException("NOT_FOUND", "parent run not found"));
            if (causal != null && (causal.lifecycle().terminal()
                    || !causal.pins().rootRunId().equals(options.causalParent().rootRunId()))) {
                throw new RunAdmissionRejectedException("PARENT_NOT_RESUMABLE", "child parent lineage is not resumable");
            }
            UUID id = UUID.randomUUID();
            RunRow run = new RunRow(id, workspace.value(), revision.definitionId(), release.versionId(),
                    revision.releaseId(), deployment.id(), revision.environmentId(), revision.id(), cohort.id(),
                    causal == null ? "ROOT" : "CHILD", routing.id(), target.ordinal(),
                    causal == null ? null : causal.id(), causal == null ? id : causal.pins().rootRunId(),
                    causal == null ? null : "CHILD", "CREATED", key == null ? null : key.hex(),
                    key == null ? null : key.version(), fingerprint, clock.now());
            if (options.invoker() != null && !options.invoker().workspaceId().equals(workspace.value())) {
                throw new RunAdmissionRejectedException("INVOKER_WORKSPACE_MISMATCH",
                        "the submitting principal acts in another workspace");
            }
            RunRow created = create(workspace, run, release, manifest, runLimits, traceId, null, input,
                    options.queue(), options.actor(), options.invoker(), revision.placement(), requiresSandbox);
            runs.insertDecision(new JdbcRunAdmissionRepository.AdmissionDecision(UUID.randomUUID(), deploymentId,
                    created.id(), causal == null ? "ROOT" : "CHILD", "ACCEPTED", "ADMITTED", revision.placement(), policy.version(),
                    requestDigest, snapshot, decisionTime));
            return AdmissionAttempt.accepted(new AdmittedRun(created, false), "ADMITTED", revision.placement());
        })));
        observe(attempt);
        if (attempt.rejectionCode() != null) {
            throw new RunAdmissionRejectedException(attempt.rejectionCode(), attempt.rejectionDetail());
        }
        return attempt.admitted();
    }

    /**
     * Admits causally linked continuation work for a non-terminal parent under the parent's exact pins. Only Run
     * Plane components hold this authority; it is not exposed to external requests.
     */
    public AdmittedRun admitContinuation(WorkspaceId workspace, UUID parentRunId, ContinuationKind kind) {
        return admitContinuation(workspace, parentRunId, kind, null);
    }

    /**
     * As {@link #admitContinuation(WorkspaceId, UUID, ContinuationKind)} with the continuation's input. The
     * continuation joins its parent's trace, keeps its parent's pins, and is created without being queued; the
     * parent's worker queues it when it should run.
     */
    public AdmittedRun admitContinuation(WorkspaceId workspace, UUID parentRunId, ContinuationKind kind, Object input) {
        Objects.requireNonNull(parentRunId, "parentRunId");
        Objects.requireNonNull(kind, "kind");
        AdmissionAttempt attempt = fencedAttempt(() -> retry.execute("run.continue", () -> tenants.execute(workspace, () -> {
            // The parent is read once, with its pins and lifecycle (#175).
            com.dvarahq.agentworks.domain.run.RunRecord parent = history.inTransaction(workspace,
                    tx -> tx.findRun(parentRunId))
                    .orElseThrow(() -> new RunAdmissionRejectedException("NOT_FOUND", "parent run not found"));
            RunPins inherited = parent.pins();
            if (parent.lifecycle().terminal()) {
                throw new RunAdmissionRejectedException("PARENT_NOT_RESUMABLE", "the parent run is terminal");
            }
            CohortRow cohort = lifecycle.findCohort(inherited.cohortId()).orElseThrow();
            if (!Set.of("ROUTABLE", "DRAINING", "QUIESCENT").contains(cohort.state())) {
                throw new RunAdmissionRejectedException("COHORT_NOT_ADMITTING", "the parent cohort is " + cohort.state());
            }
            RunRow run = new RunRow(UUID.randomUUID(), workspace.value(), inherited.definitionId(),
                    inherited.versionId(), inherited.releaseId(), inherited.deploymentId(), inherited.environmentId(),
                    inherited.revisionId(), inherited.cohortId(), "CONTINUATION", null, null, inherited.id(),
                    inherited.rootRunId(), kind.name(), "CREATED", null, null, null, clock.now());
            // A continuation inherits its parent's trace, manifest version, and limits (ADR-030: no path re-pins a
            // run); only a parent admitted before issue #32, which has none, gets fresh creation facts.
            ReleaseRow release = lifecycle.findRelease(inherited.releaseId()).orElseThrow();
            boolean legacyParent = inherited.traceId() == null;
            RunManifest manifest = manifests.read(workspace, release.id(), release.manifestJson());
            RunLimits runLimits = legacyParent ? Objects.requireNonNull(limits.limitsFor(release), "limits")
                    : inherited.limits();
            RevisionRow revision = lifecycle.findRevision(inherited.revisionId()).orElseThrow();
            boolean requiresSandbox = manifest.sandboxConfiguration().isPresent();
            Instant decisionTime = clock.now();
            runs.lockCapacity(inherited.deploymentId());
            var snapshot = runs.admissionSnapshot(inherited.deploymentId(), revision.placement(),
                    decisionTime.minus(policy.rateWindow()));
            var decision = policy.evaluate(snapshot, new RunAdmissionPolicy.Request(false, false, requiresSandbox,
                    revision.placement(), runLimits, placementConstraints(revision)));
            String requestDigest = Digests.prefixed(parentRunId + ":" + kind.name() + ":"
                    + (input == null ? "null" : CANONICAL.writeValueAsString(input)));
            if (!decision.accepted()) {
                runs.insertDecision(new JdbcRunAdmissionRepository.AdmissionDecision(UUID.randomUUID(),
                        inherited.deploymentId(), null, "CONTINUATION", decision.outcome(), decision.code(),
                        revision.placement(), policy.version(), requestDigest, snapshot, decisionTime));
                return AdmissionAttempt.rejected(externalCode(decision), decision.code() + ": " + decision.detail(),
                        decision.outcome(), revision.placement());
            }
            RunRow created = create(workspace, run, release, manifest, runLimits,
                    legacyParent ? DurableRunEngine.newTraceId() : inherited.traceId(), legacyParent ? null : inherited,
                    input, false, Options.SYSTEM_ACTOR, null, revision.placement(),
                    requiresSandbox);
            runs.insertDecision(new JdbcRunAdmissionRepository.AdmissionDecision(UUID.randomUUID(),
                    inherited.deploymentId(), created.id(), "CONTINUATION", "ACCEPTED", "ADMITTED",
                    revision.placement(), policy.version(), requestDigest, snapshot, decisionTime));
            return AdmissionAttempt.accepted(new AdmittedRun(created, false), "ADMITTED", revision.placement());
        })));
        observe(attempt);
        if (attempt.rejectionCode() != null) {
            throw new RunAdmissionRejectedException(attempt.rejectionCode(), attempt.rejectionDetail());
        }
        return attempt.admitted();
    }

    public RunRow run(WorkspaceId workspace, UUID runId) {
        return tenants.execute(workspace, () -> runs.find(runId)
                .orElseThrow(() -> new RunAdmissionRejectedException("NOT_FOUND", "run not found")));
    }

    /**
     * Deployment plus JSON input with object keys sorted at every depth; numbers keep their JSON form. An
     * authenticated actor is part of the request, as it is for lifecycle commands, so two principals reusing one
     * key and body conflict instead of receiving each other's run; system admissions keep their original form.
     */
    static String canonicalRequest(UUID deploymentId, Object input, String actor) {
        Map<String, Object> request = new TreeMap<>();
        if (actor != null && !Options.SYSTEM_ACTOR.equals(actor)) request.put("actor", actor);
        request.put("deploymentId", deploymentId.toString());
        request.put("input", input == null ? null : CANONICAL.treeToValue(CANONICAL.valueToTree(input), Object.class));
        return CANONICAL.writeValueAsString(request);
    }

    private static Map<String, String> placementConstraints(RevisionRow revision) {
        try {
            var configuration = CANONICAL.readTree(revision.configurationJson());
            var constraints = configuration.get("placementConstraints");
            if (constraints == null || constraints.isNull()) return Map.of();
            if (!constraints.isObject()) {
                throw new RunAdmissionRejectedException("PLACEMENT_INVALID", "placement constraints are not an object");
            }
            Map<String, String> result = new TreeMap<>();
            for (String field : List.of("securityZone", "dataResidency", "runtimeClass", "imageClass")) {
                var value = constraints.get(field);
                if (value != null && !value.isNull()) {
                    if (!value.isString()) throw new RunAdmissionRejectedException("PLACEMENT_INVALID",
                            "placement constraint " + field + " is not text");
                    result.put(field, value.stringValue());
                }
            }
            return Map.copyOf(result);
        } catch (RunAdmissionRejectedException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw new RunAdmissionRejectedException("PLACEMENT_INVALID", "deployment placement is unreadable");
        }
    }

    private static RunLimits narrower(RunLimits requested, RunLimits ceiling) {
        if (greater(requested.maxTotalTokens(), ceiling.maxTotalTokens())
                || greater(requested.maxModelCalls(), ceiling.maxModelCalls())
                || greater(requested.maxToolCalls(), ceiling.maxToolCalls())
                || greater(requested.maxRuntimeMillis(), ceiling.maxRuntimeMillis())
                || ceiling.maxCostUsd() != null && (requested.maxCostUsd() == null
                    || requested.maxCostUsd().compareTo(ceiling.maxCostUsd()) > 0)) {
            throw new RunAdmissionRejectedException("CHILD_BUDGET_EXCEEDS_RELEASE",
                    "the delegated child limits exceed the target release limits");
        }
        return requested;
    }

    private static boolean greater(Long requested, Long ceiling) {
        return ceiling != null && (requested == null || requested > ceiling);
    }

    /**
     * Inserts the run with its creation facts and appends {@code RUN_CREATED} (and {@code RUN_QUEUED} when queued) in
     * the admission transaction. The manifest is read here, so an unreadable release is refused before any row exists.
     */
    private RunRow create(WorkspaceId workspace, RunRow run, ReleaseRow release, RunManifest manifest,
            RunLimits runLimits, String traceId, RunPins parent, Object input, boolean queue, String actor,
            com.dvarahq.agentworks.domain.run.RunInvoker invoker, String placement, boolean requiresSandbox) {
        int manifestVersion = manifest.manifestVersion();
        runLimits = parent != null ? parent.limits() : runLimits;
        if (parent != null && parent.manifestVersion() != manifestVersion) {
            throw new RunAdmissionRejectedException("CONTINUATION_PROVENANCE_MISMATCH",
                    "the parent's release manifest no longer matches its pinned version");
        }
        Instant createdAt = run.createdAt();
        RunPins pins = new RunPins(run.id(), run.workspaceId(), run.definitionId(), run.versionId(), run.releaseId(),
                run.deploymentId(), run.environmentId(), run.revisionId(), run.cohortId(), run.admission(),
                run.routingGenerationId(), run.routingTargetOrdinal(), run.parentRunId(), run.rootRunId(),
                run.continuationKind(), traceId, manifestVersion, runLimits, createdAt);
        PreparedCreation creation = engine.prepareCreation(pins, input, createdAt, actor, invoker);
        runs.insert(run, new JdbcRunAdmissionRepository.RunCreation(traceId, manifestVersion, runLimits,
                creation.event().eventDigest(), manifest.executorType(), placement, requiresSandbox, policy.version()));
        history.inTransaction(workspace, tx -> {
            engine.recordCreation(tx, creation, queue, actor);
            return null;
        });
        return queue ? runs.find(run.id()).orElseThrow() : run;
    }

    private static String trace(String traceId) {
        try {
            return RunEventCodec.traceId(traceId);
        } catch (RunEngineException invalid) {
            throw new RunAdmissionRejectedException("INVALID_TRACE_ID", invalid.getMessage());
        }
    }

    /** Translates cohort-fence trigger rejections into typed admission failures. */
    private static AdmittedRun fenced(java.util.function.Supplier<AdmittedRun> admission) {
        try {
            return admission.get();
        } catch (RunAuthorityException rejected) {
            throw new RunAdmissionRejectedException(rejected.code(), rejected.detail() == null
                    ? "the cohort fence rejected the admission" : "the cohort is " + rejected.detail());
        } catch (RunEngineException refused) {
            throw new RunAdmissionRejectedException(refused.code(), refused.getMessage());
        } catch (DataIntegrityViolationException rejected) {
            Matcher code = FENCE_CODE.matcher(String.valueOf(rejected.getMostSpecificCause().getMessage()));
            if (code.find()) {
                throw new RunAdmissionRejectedException(code.group(1), code.group(2) == null
                        ? "the cohort fence rejected the admission" : "the cohort is " + code.group(2));
            }
            throw rejected;
        }
    }

    private record AdmissionAttempt(AdmittedRun admitted, String rejectionCode, String rejectionDetail,
            String outcome, String metricCode, String placement) {
        static AdmissionAttempt accepted(AdmittedRun admitted, String code, String placement) {
            return new AdmissionAttempt(admitted, null, null, "ACCEPTED", code, placement);
        }
        static AdmissionAttempt rejected(String code, String detail, String outcome, String placement) {
            return new AdmissionAttempt(null, code, detail, outcome, code, placement);
        }
    }

    private static String externalCode(RunAdmissionPolicy.Result decision) {
        return "DEFERRED".equals(decision.outcome()) ? "ADMISSION_DEFERRED" : decision.code();
    }

    private void observe(AdmissionAttempt attempt) {
        try {
            observer.decided(attempt.outcome(), attempt.metricCode(), attempt.placement());
        } catch (RuntimeException ignored) {
            // Telemetry is non-authoritative and cannot turn a committed admission or refusal into another outcome.
        }
    }

    private static AdmissionAttempt fencedAttempt(java.util.function.Supplier<AdmissionAttempt> admission) {
        try {
            return admission.get();
        } catch (RunAuthorityException rejected) {
            throw new RunAdmissionRejectedException(rejected.code(), rejected.detail() == null
                    ? "the cohort fence rejected the admission" : "the cohort is " + rejected.detail());
        } catch (RunEngineException refused) {
            throw new RunAdmissionRejectedException(refused.code(), refused.getMessage());
        } catch (DataIntegrityViolationException rejected) {
            Matcher code = FENCE_CODE.matcher(String.valueOf(rejected.getMostSpecificCause().getMessage()));
            if (code.find()) {
                throw new RunAdmissionRejectedException(code.group(1), code.group(2) == null
                        ? "the cohort fence rejected the admission" : "the cohort is " + code.group(2));
            }
            throw rejected;
        }
    }
}
