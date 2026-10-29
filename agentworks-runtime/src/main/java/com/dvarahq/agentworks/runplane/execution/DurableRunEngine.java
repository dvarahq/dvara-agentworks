package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.AgentStep;
import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.DurableRunStore;
import com.dvarahq.agentworks.domain.run.RunAuthorityException;
import com.dvarahq.agentworks.domain.run.RunBlockReason;
import com.dvarahq.agentworks.domain.run.RunCheckpoint;
import com.dvarahq.agentworks.domain.run.RunControl;
import com.dvarahq.agentworks.domain.run.RunEffect;
import com.dvarahq.agentworks.domain.run.RunEvent;
import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.RunLease;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.run.RunLimits;
import com.dvarahq.agentworks.domain.run.RunPins;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.run.RunStoreConflictException;
import com.dvarahq.agentworks.domain.run.RunSummary;
import com.dvarahq.agentworks.domain.run.RunTransaction;
import com.dvarahq.agentworks.domain.run.RunUsage;
import com.dvarahq.agentworks.domain.run.WorkItem;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.interaction.HumanInteractionKind;
import com.dvarahq.agentworks.interaction.HumanInteractionPrompt;
import com.dvarahq.agentworks.interaction.HumanInteractionRecord;
import com.dvarahq.agentworks.interaction.HumanInteractionResponse;
import com.dvarahq.agentworks.operation.DurableOperationRecord;
import com.dvarahq.agentworks.operation.DurableOperationStatus;
import com.dvarahq.agentworks.child.ChildRunRecord;
import com.dvarahq.agentworks.child.ScheduledWakeupRecord;
import com.dvarahq.agentworks.runplane.RunPlaneComponent;
import com.dvarahq.agentworks.runplane.execution.ReleaseManifestReader.RunManifest;
import com.dvarahq.agentworks.runplane.execution.RunEventCodec.DecodedEvent;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The durable run engine (ADR-003, ADR-005, ADR-027). Every durable transition appends immutable, ordered, versioned
 * events in the same transaction as the reconstructible run summary, the checkpoint, the effect ledger, and the
 * {@code cohort_work_item} ledger. Ownership is a lease in PostgreSQL time with a fence that increments on every
 * claim, so a stale holder cannot write; blocked and paused runs hold no lease and consume no worker thread.
 *
 * <p>The engine is stateless and serves every agent, tenant, deployment, and release: each operation loads the
 * run's exact pins from the store, and the only cross-tenant operation is selecting the next claimable run. It
 * depends only on the {@link DurableRunStore} port.
 *
 * <p>Lock order inside one transaction is run, then cohort (through the database fence); no operation locks more
 * than one run. Transient serialization failures, deadlocks, and lock timeouts retry the whole transaction a bounded
 * number of times and leave nothing committed on exhaustion.
 */
public final class DurableRunEngine implements RunPlaneComponent {
    public static final Duration DEFAULT_LEASE = Duration.ofSeconds(30);
    public static final Duration MAX_LEASE = Duration.ofMinutes(10);
    /** The most a heartbeat defers a run's runtime limit for an in-flight effect. */
    public static final Duration MAX_RUNTIME_GRACE = Duration.ofMinutes(10);
    public static final String ENGINE_ACTOR = "system:run-engine";
    /** The actor of internal admissions; {@code RunAdmissionService.Options.SYSTEM_ACTOR} refers to it. */
    public static final String ADMISSION_ACTOR = "system:admission";
    /** Version of the checkpoint envelope this node writes and reads. */
    public static final int CHECKPOINT_VERSION = 1;
    /** Top-level payload keys only engine events may carry; the summary projection reads them. */
    private static final List<String> RESERVED_WORKER_KEYS = List.of("runtimeMillis", "fence", "checkpointSequence");
    private static final Pattern EFFECT_KEY = Pattern.compile("[A-Za-z0-9:._/-]{1,200}");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A worker-authored event to append. */
    public record EventDraft(RunEventType type, Object payload, String spanId) {
        public EventDraft {
            Objects.requireNonNull(type, "type");
        }

        public static EventDraft of(RunEventType type, Object payload) {
            return new EventDraft(type, payload, null);
        }
    }

    /** The checkpoint a claim restores: the handler state at the checkpoint's event position. */
    public record RestoredCheckpoint(long sequence, long eventSequence, JsonNode state) {}

    /**
     * A claimed continuation, with everything a stateless worker needs, loaded from the run's exact pins.
     * {@code invoker} is the principal that submitted the run, when one did (issue #34), and {@code agentVersion} is
     * the pinned AgentVersion, present only when its stored digest equals the manifest's. {@code pauseRequested} is
     * true when an operator asked for the run to pause (#183) before this claim, for example while an earlier worker
     * held it: the claim itself is a safe boundary, so the worker pauses before doing any work.
     */
    public record ClaimedRun(RunLease lease, RunRecord run, RunManifest manifest, JsonNode input,
            Optional<RestoredCheckpoint> checkpoint, boolean recovered, String cohortState,
            Optional<com.dvarahq.agentworks.domain.run.RunInvoker> invoker,
            Optional<com.dvarahq.agentworks.domain.run.PinnedAgentVersion> agentVersion,
            Map<String, com.dvarahq.agentworks.domain.security.CredentialRef> credentialReferences,
            boolean pauseRequested, Duration recoveryDelay) {
        public ClaimedRun {
            credentialReferences = Map.copyOf(Objects.requireNonNull(credentialReferences, "credentialReferences"));
        }
    }

    /**
     * The result of a lease-holder write. {@code lease} is the token for the holder's next write; it is null when
     * the write ended the run (a limit was exceeded). {@code cohortState} tells the worker whether its cohort was
     * suspended, and {@code pauseRequested} whether an operator asked for the run to pause (#183); in either case it
     * should pause at its next safe boundary.
     */
    public record WriteResult(RunLease lease, RunRecord run, String cohortState, boolean pauseRequested) {
        public boolean ended() {
            return lease == null;
        }
    }

    /**
     * The outcome of an operator's pause request (#183): the run as it now is, what the request did, and the run's
     * in-doubt effects, which keep a pending pause from taking effect until their outcome is recorded.
     */
    public record PauseResult(RunRecord run, Outcome outcome, List<String> inDoubtEffects, String standingRequester,
            boolean firstRepeat) {
        /**
         * {@code standingRequester} is, for {@code REPEATED}, the actor whose earlier request stands; else null.
         * {@code firstRepeat} is, for {@code REPEATED}, whether this is the first repeat of the standing request by
         * this actor, who did not make it (#201); else false.
         */
        public PauseResult {
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(outcome, "outcome");
            inDoubtEffects = List.copyOf(inDoubtEffects);
        }

        public PauseResult(RunRecord run, Outcome outcome, List<String> inDoubtEffects) {
            this(run, outcome, inDoubtEffects, null, false);
        }

        /** Whether the pause is still pending: the run is active and pauses at its next safe boundary. */
        public boolean pending() {
            return outcome == Outcome.REQUESTED || outcome == Outcome.REPEATED;
        }

        public enum Outcome {
            /** The run paused now. */
            PAUSED,
            /** The run was already paused; nothing changed. */
            ALREADY_PAUSED,
            /** A new request was recorded for the active run. */
            REQUESTED,
            /** The active run already had a request, which stands. */
            REPEATED
        }
    }

    /**
     * The reason a run's RUN_PAUSED event records when an operator requested the pause (#183). It is reserved: a pause
     * with this reason takes effect only while a request exists ({@code PAUSE_NOT_REQUESTED} otherwise).
     */
    public static final String OPERATOR_PAUSE = "OPERATOR_REQUESTED";

    /**
     * The recorded outcome of a modeled external effect: its committed {@code result}, or the {@code failure} it
     * definitely failed with. {@code replayed} is true when the outcome was recorded earlier and nothing was invoked.
     * {@code pauseRequested} tells the holder whether an operator's pause request stood when the outcome was recorded
     * or replayed (#183, #194), so its next safe boundary observes a request made during the effect.
     */
    public record EffectOutcome(JsonNode result, String failure, boolean replayed, RunLease lease, String externalKey,
            boolean pauseRequested) {
        public boolean failed() {
            return failure != null;
        }
    }

    /**
     * A renewed lease, and whether an operator's pause request stood when it was renewed (#194): a holder that writes
     * nothing between boundaries still observes a request within one heartbeat.
     */
    public record Renewal(RunLease lease, boolean pauseRequested) {}

    /**
     * The answer of a safe boundary (#183, #194): the run it paused, or null when nothing changed; and, when nothing
     * changed, whether the request still stands. It stands while an effect is in doubt, so the holder keeps asking at
     * its later boundaries and pauses once the effect's outcome is recorded. It no longer stands once a resume withdrew
     * it, or once it was cleared for an executor that cannot resume mid-exchange.
     */
    public record BoundaryPause(RunRecord paused, boolean requestStands) {
        public boolean pausedNow() {
            return paused != null;
        }
    }

    /**
     * The effect was invoked but its outcome is unknown. It stays pending and in doubt, is retried only with the same
     * external key, and keeps the cohort from quiescing; {@code lease} is the holder's current token.
     */
    public static final class EffectInDoubtException extends RuntimeException {
        private final String effectKey;
        private final RunLease lease;

        public EffectInDoubtException(String effectKey, RunLease lease, Throwable cause) {
            super("effect " + effectKey + " outcome is unknown; it stays pending and is retried only with the same "
                    + "external key", cause);
            this.effectKey = effectKey;
            this.lease = lease;
        }

        public String code() {
            return "EFFECT_IN_DOUBT";
        }

        public String effectKey() {
            return effectKey;
        }

        public RunLease lease() {
            return lease;
        }
    }

    /** Invokes a modeled external effect; {@code externalKey} is stable across retries and deduplicates it. */
    @FunctionalInterface
    public interface EffectInvoker {
        JsonNode invoke(String externalKey, JsonNode request) throws Exception;
    }

    /** Thrown by an {@link EffectInvoker} when the effect definitely did not apply; the failure is recorded. */
    public static final class EffectFailedException extends RuntimeException {
        public EffectFailedException(String message) {
            super(message);
        }
    }

    /** Integrity diagnostics: the rebuilt summary and every finding; nothing is repaired or invented. */
    public record IntegrityReport(UUID runId, boolean intact, List<String> findings, RunSummary persisted,
            RunSummary rebuilt) {}

    /** A creation event prepared before the run row is inserted, so the row can record its digest. */
    public record PreparedCreation(RunPins pins, RunEvent event) {}

    private static final System.Logger LOG = System.getLogger(DurableRunEngine.class.getName());

    private final DurableRunStore store;
    private final RunEventCodec codec;
    private final ReleaseManifestReader manifests;
    private final LifecycleRetry retry;
    private final String compatibility;
    private final ExecutorCapabilities.Registry executors;
    private final RunRecoveryObserver recoveryObserver;

    public DurableRunEngine(DurableRunStore store, RunEventCodec codec, ReleaseManifestReader manifests,
            LifecycleRetry retry) {
        this(store, codec, manifests, retry, compatibilityOf(codec));
    }

    /**
     * @param compatibility this node's runtime compatibility identity, which scopes the claim quarantines it records
     *     and honors (#181); normally {@link #compatibilityOf(RunEventCodec)}
     */
    public DurableRunEngine(DurableRunStore store, RunEventCodec codec, ReleaseManifestReader manifests,
            LifecycleRetry retry, String compatibility) {
        this(store, codec, manifests, retry, compatibility, ExecutorCapabilities.Registry.NONE_DECLARED);
    }

    /**
     * @param executors what each executor type promises (#183): an operator pauses an active run only when its pinned
     *     executor type is resumable mid-exchange; unknown types promise nothing
     */
    public DurableRunEngine(DurableRunStore store, RunEventCodec codec, ReleaseManifestReader manifests,
            LifecycleRetry retry, String compatibility, ExecutorCapabilities.Registry executors) {
        this(store, codec, manifests, retry, compatibility, executors, RunRecoveryObserver.NONE);
    }

    public DurableRunEngine(DurableRunStore store, RunEventCodec codec, ReleaseManifestReader manifests,
            LifecycleRetry retry, String compatibility, ExecutorCapabilities.Registry executors,
            RunRecoveryObserver recoveryObserver) {
        this.store = Objects.requireNonNull(store, "store");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.compatibility = com.dvarahq.agentworks.domain.run.ClaimQuarantine.compatibility(compatibility);
        this.executors = Objects.requireNonNull(executors, "executors");
        this.recoveryObserver = Objects.requireNonNull(recoveryObserver, "recoveryObserver");
    }

    /**
     * Whether this node promises that a run of {@code manifest}'s executor resumes mid-exchange without repeating work,
     * so an operator's request can pause it while active (#183). Nothing is read from the database.
     */
    public boolean resumableMidExchange(RunManifest manifest) {
        return executors.apply(Objects.requireNonNull(manifest, "manifest").executorType()).resumableMidExchange();
    }

    /** Whether the run's pinned executor resumes a mid-exchange pause without repeating work (#183). */
    private boolean resumableMidExchange(RunTransaction tx, RunRecord run) {
        String manifest = tx.releaseManifest(run.pins().releaseId()).orElse(null);
        if (manifest == null) return false;
        try {
            return resumableMidExchange(manifests.read(tx.workspace(), run.pins().releaseId(), manifest));
        } catch (RunEngineException unreadable) {
            return false;
        }
    }

    /**
     * The runtime compatibility identity of a node that reads histories with {@code codec}: its build (the version and
     * commit it was built from, {@link BuildIdentity}, #185) and a digest of everything that decides whether it can
     * interpret a run, namely that build, the current version of every event type, the checkpoint version, and the
     * release-manifest versions it reads. Two nodes with the same identity refuse
     * the same runs, so a claim quarantine recorded by one applies to all of them and to no other node (#181).
     */
    public static String compatibilityOf(RunEventCodec codec) {
        return compatibilityOf(codec, BuildIdentity.CURRENT);
    }

    /**
     * {@link #compatibilityOf(RunEventCodec)} for an unstamped build that the operator names explicitly
     * ({@code agentworks.runtime.build-id}, #185): 1 to 64 characters of {@code [A-Za-z0-9._+-]}, which must differ
     * between builds. The project version is folded in, so one name left in place across versions never merges them
     * (#189). A stamped build never uses it.
     */
    public static String compatibilityOf(RunEventCodec codec, String buildId) {
        return compatibilityOf(codec, BuildIdentity.configured(buildId,
                DurableRunEngine.class.getPackage().getImplementationVersion()));
    }

    /**
     * Whether this node's runtime module carries the build stamp of a Maven build from a git checkout (#185). Without
     * it, every build of this version shares one compatibility identity; see {@link BuildIdentity}.
     */
    public static boolean buildStamped() {
        return BuildIdentity.CURRENT.stamped();
    }

    /** {@link #compatibilityOf(RunEventCodec)} for the given build. */
    static String compatibilityOf(RunEventCodec codec, BuildIdentity build) {
        StringBuilder facts = new StringBuilder("events=");
        for (RunEventType type : RunEventType.values()) {
            facts.append(type.name()).append(':').append(codec.schemas().currentVersion(type)).append(',');
        }
        facts.append(";checkpoint=").append(CHECKPOINT_VERSION).append(";manifests=")
                .append(new java.util.TreeSet<>(ReleaseManifestReader.SUPPORTED_VERSIONS))
                .append(";build=").append(build.fingerprint());
        return "agentworks-" + build.label() + "-" + RunEventCodec.sha256(facts.toString()).substring(
                "sha256:".length(), "sha256:".length() + 16);
    }

    /**
     * The build a node runs (#185), as stamped by the Maven build of the runtime module into
     * {@code META-INF/agentworks/agentworks-runtime/build-identity.properties}: the commit the runtime module was built
     * from and, when the working tree differed from that commit, the build time too, so every build of a changed tree
     * is its own identity. The jar's {@code Implementation-Version} alone is the project version, shared by every
     * build. The reactor builds every module from one checkout, so the runtime module's commit is the node's.
     *
     * <p>A build without a recorded commit (built without git or outside a git repository, or compiled by an IDE into
     * an output directory that no Maven build has stamped) falls back deterministically to the project version, or
     * {@code dev} when that is unknown too, which is the identity every such build of that version shares. An IDE that
     * compiles into a directory a Maven build stamped keeps that stamp, so its classes claim the stamped commit; the
     * identity is a production safeguard, and local development can release quarantines. Outside local development,
     * the server refuses to start unstamped unless {@code agentworks.runtime.build-id} names the build
     * ({@link #configured}).
     *
     * @param label the readable part of the identity: {@code <version>[+<commit prefix>[.dirty]]}
     * @param fingerprint what else tells builds apart, folded into the identity's digest: the full commit, and the
     *     build time of a changed tree
     */
    record BuildIdentity(String label, String fingerprint) {
        static final String RESOURCE = "/META-INF/agentworks/agentworks-runtime/build-identity.properties";
        private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9._+-]{1,64}");
        private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
        private static final Pattern BUILT = Pattern.compile("[0-9A-Za-z:.+-]{1,64}");
        /** This node's build; declared after the patterns it is computed with. */
        static final BuildIdentity CURRENT = of(stamp(), DurableRunEngine.class.getPackage().getImplementationVersion());

        /** Whether the build carries a commit stamp, or was named explicitly. */
        boolean stamped() {
            return !"unstamped".equals(fingerprint);
        }

        /**
         * An unstamped build named by the operator, of jar {@code version}; see
         * {@link DurableRunEngine#compatibilityOf(RunEventCodec, String)}.
         */
        static BuildIdentity configured(String buildId, String version) {
            if (buildId == null || !VERSION.matcher(buildId).matches()) {
                throw new IllegalArgumentException("agentworks.runtime.build-id is 1 to 64 characters of [A-Za-z0-9._+-]");
            }
            String base = version != null && VERSION.matcher(version).matches() ? version : "dev";
            return new BuildIdentity(base + "+build." + buildId, "configured:" + base + ":" + buildId);
        }

        /** The identity of a build with {@code stamp} (empty when none was stamped) and jar {@code version}. */
        static BuildIdentity of(java.util.Properties stamp, String version) {
            String base = version != null && VERSION.matcher(version).matches() ? version : "dev";
            String commit = stamp.getProperty("commit", "").strip();
            if (!COMMIT.matcher(commit).matches()) return new BuildIdentity(base, "unstamped");
            if ("true".equals(stamp.getProperty("clean", "").strip())) {
                return new BuildIdentity(base + "+" + commit.substring(0, 12), "commit:" + commit);
            }
            String built = stamp.getProperty("built", "").strip();
            return new BuildIdentity(base + "+" + commit.substring(0, 12) + ".dirty", "commit:" + commit + ":dirty:"
                    + (BUILT.matcher(built).matches() ? built : "unknown"));
        }

        private static java.util.Properties stamp() {
            java.util.Properties stamp = new java.util.Properties();
            try (var in = DurableRunEngine.class.getResourceAsStream(RESOURCE)) {
                if (in != null) stamp.load(in);
            } catch (java.io.IOException | IllegalArgumentException unreadable) {
                stamp.clear();
            }
            return stamp;
        }
    }

    /** This node's runtime compatibility identity (#181). */
    public String compatibility() {
        return compatibility;
    }

    public RunEventCodec codec() {
        return codec;
    }

    // Creation

    /** A new random W3C trace id. */
    public static String newTraceId() {
        return random(16);
    }

    /**
     * Prepares the {@code RUN_CREATED} event that pins every creation fact, including the canonical input. The
     * manifest version must be one this node reads, so a node never admits work it could not load.
     */
    public PreparedCreation prepareCreation(RunPins pins, Object input, Instant now) {
        return prepareCreation(pins, input, now, ADMISSION_ACTOR);
    }

    /** As {@link #prepareCreation(RunPins, Object, Instant)}, recording {@code actor} on {@code RUN_CREATED}. */
    public PreparedCreation prepareCreation(RunPins pins, Object input, Instant now, String actor) {
        return prepareCreation(pins, input, now, actor, null);
    }

    /**
     * As {@link #prepareCreation(RunPins, Object, Instant, String)}, also recording the authenticated principal that
     * submitted the run, if any, as {@code RUN_CREATED.invoker} (identifiers only), so a worker can invoke models on its
     * behalf (issue #34). The field is optional and additive in payload version 1: runs admitted without a principal,
     * and runs admitted before issue #34, have none.
     */
    public PreparedCreation prepareCreation(RunPins pins, Object input, Instant now, String actor,
            com.dvarahq.agentworks.domain.run.RunInvoker invoker) {
        Objects.requireNonNull(pins, "pins");
        RunEventCodec.identity(actor, "actor");
        RunEventCodec.traceId(pins.traceId());
        if (pins.manifestVersion() == null || !ReleaseManifestReader.SUPPORTED_VERSIONS.contains(pins.manifestVersion())) {
            throw new RunEngineException("UNSUPPORTED_MANIFEST_VERSION", "manifest version " + pins.manifestVersion()
                    + " is not supported by this node");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("admission", pins.admission());
        put(payload, "continuationKind", pins.continuationKind());
        put(payload, "parentRunId", pins.parentRunId());
        payload.put("rootRunId", pins.rootRunId().toString());
        payload.put("definitionId", pins.definitionId().toString());
        payload.put("versionId", pins.versionId().toString());
        payload.put("releaseId", pins.releaseId().toString());
        payload.put("deploymentId", pins.deploymentId().toString());
        payload.put("environmentId", pins.environmentId().toString());
        payload.put("revisionId", pins.revisionId().toString());
        payload.put("cohortId", pins.cohortId().toString());
        put(payload, "routingGenerationId", pins.routingGenerationId());
        put(payload, "routingTargetOrdinal", pins.routingTargetOrdinal());
        payload.put("manifestVersion", pins.manifestVersion());
        payload.put("limits", limits(pins.limits()));
        payload.put("input", input == null ? NullNode.getInstance() : codec.mapper().valueToTree(input));
        if (invoker != null) {
            if (!invoker.workspaceId().equals(pins.workspaceId())) {
                throw new RunEngineException("INVALID_EVENT", "the invoker acts in another workspace");
            }
            Map<String, Object> principal = new LinkedHashMap<>();
            principal.put("organizationId", invoker.organizationId().toString());
            principal.put("workspaceId", invoker.workspaceId().toString());
            principal.put("subjectId", invoker.subjectId().toString());
            principal.put("credentialId", invoker.credentialId().toString());
            principal.put("method", invoker.method());
            payload.put("invoker", principal);
        }
        Batch batch = new Batch(pins, 0, null, now);
        RunEvent created = batch.add(RunEventType.RUN_CREATED, payload, actor, null, 0, null);
        return new PreparedCreation(pins, created);
    }

    /**
     * Appends the prepared creation event after the run row is inserted, in the caller's transaction, and queues
     * the run when {@code queue} is set.
     */
    public void recordCreation(RunTransaction tx, PreparedCreation creation, boolean queue, String actor) {
        tx.appendEvents(List.of(creation.event()));
        if (queue) {
            RunRecord run = tx.lockRun(creation.pins().id()).orElseThrow(() -> notFound(creation.pins().id()));
            queue(tx, run, actor);
        }
    }

    // Control operations (no lease)

    /** CREATED → QUEUED; idempotent for a queued run. */
    public RunRecord enqueue(WorkspaceId workspace, UUID runId, String actor) {
        RunEventCodec.identity(actor, "actor");
        return call("run.enqueue", workspace, tx -> {
            RunRecord run = lock(tx, runId);
            if (run.lifecycle() == RunLifecycle.QUEUED) return run;
            return queue(tx, run, actor);
        });
    }

    /**
     * BLOCKED or PAUSED → QUEUED so any worker can claim it; idempotent for a queued run. An active run is refused
     * with {@code RUN_ACTIVE} so that a completion racing the worker is retried rather than lost.
     */
    public RunRecord resume(WorkspaceId workspace, UUID runId, String actor) {
        return resume(workspace, runId, actor, false);
    }

    /**
     * Resumes a blocked run only when its latest event proves that it released its lease for this exact durable
     * interaction. The interaction's immutable pins must also match the run, preventing a late response from
     * repinning or resurrecting another run/cohort.
     */
    RunRecord resumeInteraction(WorkspaceId workspace, HumanInteractionRecord interaction, String actor) {
        Objects.requireNonNull(interaction, "interaction");
        if (!interaction.status().terminal()) {
            throw new RunEngineException("INTERACTION_OPEN", "an open interaction cannot resume a run");
        }
        RunEventCodec.identity(actor, "actor");
        var request = interaction.request();
        if (!workspace.value().equals(request.workspaceId())) {
            throw new RunEngineException("INTERACTION_PINS_MISMATCH", "interaction belongs to another workspace");
        }
        return call("run.resume-interaction", workspace, tx -> {
            RunRecord run = interactionBlock(tx, interaction);
            RunBlockReason expectedReason = request.prompt().kind() == HumanInteractionKind.AUTHORIZATION
                    ? RunBlockReason.AUTHORIZATION : RunBlockReason.HUMAN_INTERACTION;
            Instant now = tx.now();
            Batch batch = new Batch(run, now);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("from", RunLifecycle.BLOCKED.name());
            payload.put("blockReason", expectedReason.name());
            payload.put("interactionId", request.id().toString());
            payload.put("interactionStatus", interaction.status().name());
            Map<String, Object> outcome = new LinkedHashMap<>();
            outcome.put("interactionId", request.id().toString());
            outcome.put("kind", request.prompt().kind().name());
            outcome.put("correlationId", request.correlationId());
            outcome.put("status", interaction.status().name());
            outcome.put("resolvedAt", interaction.resolvedAt().orElseThrow().toString());
            if (request.prompt() instanceof HumanInteractionPrompt.Authorization authorization) {
                outcome.put("policyId", authorization.policyId());
            }
            batch.add(interactionOutcomeEvent(interaction), outcome, actor, null,
                    run.summary().leaseFence(), null);
            batch.add(RunEventType.RUN_RESUMED, payload, actor, null, run.summary().leaseFence(), null);
            RunRecord updated = run.with(summary(run.summary(), RunLifecycle.QUEUED, null, null, batch,
                    run.summary().checkpointSequence(), run.summary().leaseFence(), run.summary().usage(),
                    run.summary().startedAt(), null), control(run, null, null, null, now));
            tx.updateRun(run, updated);
            tx.appendEvents(batch.events);
            return updated;
        });
    }

    private static RunEventType interactionOutcomeEvent(HumanInteractionRecord interaction) {
        return switch (interaction.status()) {
            case OPEN -> throw new RunEngineException("INTERACTION_OPEN", "an open interaction has no outcome event");
            case EXPIRED -> RunEventType.HUMAN_INTERACTION_EXPIRED;
            case CANCELLED -> RunEventType.HUMAN_INTERACTION_CANCELLED;
            case REVOKED -> {
                if (interaction.request().prompt().kind() != HumanInteractionKind.AUTHORIZATION) {
                    throw new RunEngineException("INVALID_INTERACTION_OUTCOME",
                            "only an authorization interaction can be revoked");
                }
                yield RunEventType.AUTHORIZATION_REVOKED;
            }
            case RESPONDED -> {
                if (interaction.request().prompt().kind() != HumanInteractionKind.AUTHORIZATION) {
                    yield RunEventType.HUMAN_INTERACTION_RESPONDED;
                }
                HumanInteractionResponse.Authorization response = (HumanInteractionResponse.Authorization)
                        interaction.response().orElseThrow();
                yield response.granted() ? RunEventType.AUTHORIZATION_GRANTED : RunEventType.AUTHORIZATION_FAILED;
            }
        };
    }

    /** Locks the run before an interaction outcome locks its cohort, preserving the engine's run-then-cohort order. */
    void lockInteractionOutcome(WorkspaceId workspace, HumanInteractionRecord interaction) {
        Objects.requireNonNull(interaction, "interaction");
        if (!workspace.value().equals(interaction.request().workspaceId())) {
            throw new RunEngineException("INTERACTION_PINS_MISMATCH", "interaction belongs to another workspace");
        }
        call("run.lock-interaction-outcome", workspace, tx -> interactionBlock(tx, interaction));
    }

    private RunRecord interactionBlock(RunTransaction tx, HumanInteractionRecord interaction) {
        var request = interaction.request();
        RunRecord run = lock(tx, request.runId());
        if (!run.pins().releaseId().equals(request.releaseId())
                || !run.pins().deploymentId().equals(request.deploymentId())
                || !run.pins().revisionId().equals(request.revisionId())
                || !run.pins().cohortId().equals(request.cohortId())) {
            throw new RunEngineException("INTERACTION_PINS_MISMATCH", "interaction pins do not match its original run");
        }
        RunBlockReason expectedReason = request.prompt().kind() == HumanInteractionKind.AUTHORIZATION
                ? RunBlockReason.AUTHORIZATION : RunBlockReason.HUMAN_INTERACTION;
        if (run.lifecycle() != RunLifecycle.BLOCKED || run.summary().blockReason() != expectedReason) {
            throw new RunEngineException("INTERACTION_NOT_BLOCKING", "run is not blocked for this interaction kind");
        }
        List<RunEvent> tail = tx.eventWindow(run.id(), run.summary().eventSequence(),
                run.summary().eventSequence(), 1);
        if (tail.size() != 1) {
            throw new RunEngineException("CORRUPT_HISTORY", "the run's blocking event is missing");
        }
        DecodedEvent blocked = codec.decode(tail.getFirst());
        JsonNode detail = blocked.payload().get("detail");
        if (blocked.type() != RunEventType.RUN_BLOCKED || detail == null || !detail.isObject()
                || !request.id().toString().equals(detail.path("interactionId").asString())) {
            throw new RunEngineException("INTERACTION_NOT_BLOCKING", "the run did not block for this interaction");
        }
        return run;
    }

    /** Locks the blocked run before an operation callback locks its operation and cohort rows. */
    void lockOperationOutcome(WorkspaceId workspace, DurableOperationRecord operation) {
        Objects.requireNonNull(operation, "operation");
        call("run.lock-operation-outcome", workspace, tx -> operationBlock(tx, operation));
    }

    /** Atomically records one terminal operation, its completion inbox delivery, and the pinned run's resume. */
    RunRecord resumeOperation(WorkspaceId workspace, DurableOperationRecord operation, UUID inboxMessageId,
            String actor) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(inboxMessageId, "inboxMessageId");
        if (!operation.status().terminal()) {
            throw new RunEngineException("OPERATION_OPEN", "an open durable operation cannot resume a run");
        }
        RunEventCodec.identity(actor, "actor");
        return call("run.resume-operation", workspace, tx -> {
            RunRecord run = operationBlock(tx, operation);
            Instant now = tx.now();
            Batch batch = new Batch(run, now);
            Map<String, Object> outcome = new LinkedHashMap<>();
            outcome.put("stepId", operation.id().toString());
            outcome.put("operationId", operation.id().toString());
            outcome.put("provider", operation.provider());
            outcome.put("operationType", operation.operationType());
            outcome.put("correlationId", operation.correlationId());
            outcome.put("status", operation.status().name());
            outcome.put("code", operation.result().orElseThrow().code());
            outcome.put("resolvedAt", operation.terminalAt().orElseThrow().toString());
            batch.add(operationOutcomeEvent(operation.status()), outcome, actor, null,
                    run.summary().leaseFence(), null);
            batch.add(RunEventType.INBOX_MESSAGE_RECEIVED, Map.of(
                    "messageId", inboxMessageId.toString(),
                    "operationId", operation.id().toString(),
                    "messageType", "OPERATION_COMPLETED",
                    "correlationId", operation.correlationId()), actor, null,
                    run.summary().leaseFence(), null);
            batch.add(RunEventType.RUN_RESUMED, Map.of(
                    "from", RunLifecycle.BLOCKED.name(),
                    "blockReason", RunBlockReason.DURABLE_OPERATION.name(),
                    "operationId", operation.id().toString(),
                    "operationStatus", operation.status().name()), actor, null,
                    run.summary().leaseFence(), null);
            RunRecord updated = run.with(summary(run.summary(), RunLifecycle.QUEUED, null, null, batch,
                    run.summary().checkpointSequence(), run.summary().leaseFence(), run.summary().usage(),
                    run.summary().startedAt(), null), control(run, null, null, null, now));
            tx.updateRun(run, updated);
            tx.appendEvents(batch.events);
            return updated;
        });
    }

    private static RunEventType operationOutcomeEvent(DurableOperationStatus status) {
        return switch (status) {
            case SUCCEEDED -> RunEventType.OPERATION_COMPLETED;
            case FAILED -> RunEventType.OPERATION_FAILED;
            case CANCELLED -> RunEventType.OPERATION_CANCELLED;
            case STARTING, RUNNING -> throw new RunEngineException("OPERATION_OPEN",
                    "an open operation has no terminal event");
        };
    }

    private RunRecord operationBlock(RunTransaction tx, DurableOperationRecord operation) {
        if (!tx.workspace().value().equals(operation.workspaceId())) {
            throw new RunEngineException("OPERATION_PINS_MISMATCH", "operation belongs to another workspace");
        }
        RunRecord run = lock(tx, operation.runId());
        if (!run.pins().releaseId().equals(operation.releaseId())
                || !run.pins().deploymentId().equals(operation.deploymentId())
                || !run.pins().revisionId().equals(operation.revisionId())
                || !run.pins().cohortId().equals(operation.cohortId())) {
            throw new RunEngineException("OPERATION_PINS_MISMATCH", "operation pins do not match its run");
        }
        if (run.lifecycle() != RunLifecycle.BLOCKED
                || run.summary().blockReason() != RunBlockReason.DURABLE_OPERATION) {
            throw new RunEngineException("OPERATION_NOT_BLOCKING", "run is not blocked for a durable operation");
        }
        List<RunEvent> tail = tx.eventWindow(run.id(), run.summary().eventSequence(),
                run.summary().eventSequence(), 1);
        if (tail.size() != 1) throw new RunEngineException("CORRUPT_HISTORY", "run blocking event is missing");
        DecodedEvent blocked = codec.decode(tail.getFirst());
        JsonNode detail = blocked.payload().get("detail");
        if (blocked.type() != RunEventType.RUN_BLOCKED || detail == null || !detail.isObject()
                || !operation.id().toString().equals(detail.path("operationId").asString())) {
            throw new RunEngineException("OPERATION_NOT_BLOCKING", "run did not block for this operation");
        }
        return run;
    }

    /**
     * Resumes only a PAUSED run, as an operator may. A BLOCKED run is refused with {@code RUN_BLOCKED}: it resumes
     * through the completion that unblocks it (an interaction, operation, child, or wakeup), never by request.
     */
    public RunRecord resumePaused(WorkspaceId workspace, UUID runId, String actor) {
        return resume(workspace, runId, actor, true);
    }

    private RunRecord resume(WorkspaceId workspace, UUID runId, String actor, boolean pausedOnly) {
        RunEventCodec.identity(actor, "actor");
        return call("run.resume", workspace, tx -> {
            RunRecord run = lock(tx, runId);
            // An operator's resume withdraws a pause request the run has not reached yet (#183).
            if (pausedOnly && run.lifecycle() == RunLifecycle.ACTIVE) {
                Optional<com.dvarahq.agentworks.domain.run.RunPauseRequest> pending = tx.takePauseRequest(runId);
                if (pending.isPresent()) {
                    LOG.log(System.Logger.Level.INFO, "{0} withdrew the pause of active run {1} requested by {2} at "
                            + "{3}", actor, runId, pending.get().requestedBy(), pending.get().requestedAt());
                    return run;
                }
            }
            if (run.lifecycle() == RunLifecycle.QUEUED) return run;
            if (run.lifecycle() == RunLifecycle.ACTIVE) {
                // A completion that arrives while the worker is still active must not be lost: the caller retries
                // after the worker blocks or pauses.
                throw new RunEngineException("RUN_ACTIVE", "the run is active; resume after it blocks or pauses");
            }
            if (run.lifecycle().terminal()) throw terminal(run);
            if (pausedOnly && run.lifecycle() == RunLifecycle.BLOCKED) {
                throw new RunEngineException("RUN_BLOCKED", "a blocked run resumes through the completion that unblocks it");
            }
            if (run.lifecycle() != RunLifecycle.BLOCKED && run.lifecycle() != RunLifecycle.PAUSED) {
                throw illegal(run.lifecycle(), RunLifecycle.QUEUED);
            }
            Instant now = tx.now();
            Batch batch = new Batch(run, now);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("from", run.lifecycle().name());
            put(payload, "blockReason", run.summary().blockReason());
            batch.add(RunEventType.RUN_RESUMED, payload, actor, null, run.summary().leaseFence(), null);
            RunRecord updated = run.with(summary(run.summary(), RunLifecycle.QUEUED, null, null, batch,
                    run.summary().checkpointSequence(), run.summary().leaseFence(), run.summary().usage(),
                    run.summary().startedAt(), null), control(run, null, null, null, now));
            tx.updateRun(run, updated);
            tx.appendEvents(batch.events);
            return updated;
        });
    }

    /**
     * An operator's request to pause a run at its next safe boundary (#183), under the run's row lock so it is ordered
     * with every other write of the run:
     * <ul>
     *   <li>{@code QUEUED}: paused at once, {@code QUEUED → PAUSED}, with a {@code RUN_PAUSED} event whose actor is the
     *       operator; no worker holds it, so nothing is in flight.</li>
     *   <li>{@code ACTIVE} with a live lease: the request is recorded, and the run stays active until its worker
     *       reaches its next safe boundary: never inside an effect, whose outcome is recorded first, and never while
     *       an effect is in doubt. The {@code RUN_PAUSED} that honors it consumes the request and names the operator.
     *       A repeated request keeps the first one.</li>
     *   <li>{@code ACTIVE} whose lease expired (its worker is gone and no other has recovered it): with no effect in
     *       doubt, paused at once by advancing the lease fence, as a cancellation revokes a lease, so the lost worker
     *       can never write again, whatever its executor (resuming it repeats no more than recovery would); with an
     *       effect in doubt, the request is recorded and the effects are reported, and the recovery that resolves them
     *       pauses it at its next safe boundary.</li>
     *   <li>{@code ACTIVE} otherwise, of an executor that cannot resume mid-exchange without repeating work: refused
     *       with {@code PAUSE_UNSAFE}, and nothing is written.</li>
     *   <li>{@code PAUSED}: returned unchanged.</li>
     *   <li>{@code BLOCKED}: refused with {@code RUN_BLOCKED}; a blocked run holds no worker and resumes only through the
     *       completion that unblocks it, so cancel it instead, or pause it once it resumes. A request recorded while it
     *       was active ends when it blocks, and its {@code RUN_BLOCKED} names the requester: a request exists only
     *       while the run is active.</li>
     *   <li>{@code CREATED}: refused with {@code RUN_NOT_QUEUED}; a terminal run with {@code RUN_TERMINAL}.</li>
     * </ul>
     * Whatever ends the run first (completion, failure, a limit, or cancellation) wins; its request then ends with it.
     */
    public PauseResult requestPause(WorkspaceId workspace, UUID runId, String actor, String reason) {
        RunEventCodec.identity(actor, "actor");
        String why = bounded(reason, "reason");
        return call("run.requestPause", workspace, tx -> {
            RunRecord run = lock(tx, runId);
            switch (run.lifecycle()) {
                case PAUSED -> {
                    return new PauseResult(run, PauseResult.Outcome.ALREADY_PAUSED, List.of());
                }
                case SUCCEEDED, FAILED, CANCELLED -> throw terminal(run);
                case BLOCKED -> throw new RunEngineException("RUN_BLOCKED",
                        "a blocked run holds no worker and resumes only through the completion that unblocks it");
                case CREATED -> throw new RunEngineException("RUN_NOT_QUEUED", "the run has not been queued");
                case ACTIVE -> {
                    Instant now = tx.now();
                    List<String> inDoubt = pendingEffects(tx, runId);
                    boolean expired = run.control().leaseUntil() == null || !run.control().leaseUntil().isAfter(now);
                    if (expired && inDoubt.isEmpty()) {
                        // Its worker is gone: pausing now repeats no more than the recovery it replaces would, so it
                        // is safe for every executor. A request left pending is answered by this pause.
                        Optional<com.dvarahq.agentworks.domain.run.RunPauseRequest> earlier = tx.takePauseRequest(runId);
                        return new PauseResult(pauseExpired(tx, run, actor, why, now,
                                earlier.map(com.dvarahq.agentworks.domain.run.RunPauseRequest::requestedBy).orElse(null)),
                                PauseResult.Outcome.PAUSED, List.of());
                    }
                    if (!resumableMidExchange(tx, run)) {
                        throw new RunEngineException("PAUSE_UNSAFE", "the run's executor cannot resume a paused active "
                                + "run without repeating completed model rounds or tool calls; cancel it, or pause "
                                + "it while it is queued");
                    }
                    Optional<com.dvarahq.agentworks.domain.run.RunPauseRequest> existing = tx.pauseRequest(runId);
                    if (existing.isPresent()) {
                        LOG.log(System.Logger.Level.INFO, "{0} requested the pause of active run {1}, already "
                                + "requested by {2} at {3}; the first request stands", actor, runId,
                                existing.get().requestedBy(), existing.get().requestedAt());
                        // Another actor's repeat is recorded once per standing request (#201), so identical retries
                        // add nothing; the standing requester's own repeat is not recorded.
                        boolean firstRepeat = !actor.equals(existing.get().requestedBy())
                                && tx.recordPauseRepeat(runId, actor, now);
                        return new PauseResult(run, PauseResult.Outcome.REPEATED, inDoubt, existing.get().requestedBy(),
                                firstRepeat);
                    }
                    tx.insertPauseRequest(new com.dvarahq.agentworks.domain.run.RunPauseRequest(
                            run.pins().workspaceId(), runId, actor, now, why));
                    LOG.log(System.Logger.Level.INFO, "{0} requested the pause of active run {1}", actor, runId);
                    return new PauseResult(run, PauseResult.Outcome.REQUESTED, inDoubt);
                }
                case QUEUED -> {
                    Instant now = tx.now();
                    Batch batch = new Batch(run, now);
                    Map<String, Object> payload = operatorPause(actor, now, why, RunLifecycle.QUEUED);
                    payload.put("runtimeMillis", 0);
                    payload.put("inDoubtEffects", List.of());
                    batch.add(RunEventType.RUN_PAUSED, payload, actor, null, run.summary().leaseFence(), null);
                    RunRecord updated = run.with(summary(run.summary(), RunLifecycle.PAUSED, null, null, batch,
                            run.summary().checkpointSequence(), run.summary().leaseFence(), run.summary().usage(),
                            run.summary().startedAt(), null), control(run, null, null, null, now));
                    tx.updateRun(run, updated);
                    tx.appendEvents(batch.events);
                    return new PauseResult(updated, PauseResult.Outcome.PAUSED, List.of());
                }
                default -> throw new IllegalStateException("unknown lifecycle " + run.lifecycle());
            }
        });
    }

    private static Map<String, Object> operatorPause(String actor, Instant now, String detail, RunLifecycle from) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", OPERATOR_PAUSE);
        payload.put("from", from.name());
        payload.put("requestedBy", actor);
        payload.put("requestedAt", now.toString());
        put(payload, "detail", detail);
        return payload;
    }

    /**
     * Pauses an active run whose lease expired, without a worker (#183): the fence advances, as a cancellation revokes
     * a lease, so the lost worker's late writes are refused; the expired active period is charged as recovery would.
     */
    private RunRecord pauseExpired(RunTransaction tx, RunRecord run, String actor, String detail, Instant now,
            String earlierRequester) {
        long runtime = elapsed(run.control().activeSince(), earliest(now, run.control().leaseUntil()));
        long fence = run.summary().leaseFence() + 1;
        RunUsage usage = run.summary().usage().plusRuntime(runtime);
        Batch batch = new Batch(run, now);
        Map<String, Object> payload = operatorPause(actor, now, detail, RunLifecycle.ACTIVE);
        payload.put("leaseExpired", true);
        put(payload, "pauseRequestedBy", earlierRequester);
        payload.put("previousOwner", run.control().leaseOwner());
        payload.put("fence", fence);
        payload.put("runtimeMillis", runtime);
        payload.put("inDoubtEffects", List.of());
        batch.add(RunEventType.RUN_PAUSED, payload, actor, null, fence, null);
        RunRecord updated = run.with(summary(run.summary(), RunLifecycle.PAUSED, null, null, batch,
                run.summary().checkpointSequence(), fence, usage, run.summary().startedAt(), null),
                control(run, null, null, null, now));
        tx.updateRun(run, updated);
        tx.appendEvents(batch.events);
        closeItems(tx, run.id(), Set.of(WorkItem.Category.CLAIM), now);
        return updated;
    }

    /**
     * Cancels a non-terminal run; idempotent for a cancelled run. An active run's lease is revoked by advancing
     * the fence, so its worker's next write fails as stale and it stops at that boundary. Pending effects stay
     * explicitly in doubt (and keep the cohort from quiescing) until resolved; nothing is reported as rolled back.
     */
    public RunRecord cancel(WorkspaceId workspace, UUID runId, String actor, String reason) {
        RunEventCodec.identity(actor, "actor");
        String why = bounded(reason, "reason");
        return call("run.cancel", workspace, tx -> {
            RunRecord run = lock(tx, runId);
            if (run.lifecycle() == RunLifecycle.CANCELLED) return run;
            if (run.lifecycle().terminal()) throw terminal(run);
            Instant now = tx.now();
            boolean active = run.lifecycle() == RunLifecycle.ACTIVE;
            long runtime = active ? elapsed(run.control().activeSince(), earliest(now, run.control().leaseUntil())) : 0;
            long fence = active ? run.summary().leaseFence() + 1 : run.summary().leaseFence();
            RunUsage usage = run.summary().usage().plusRuntime(runtime);
            Batch batch = new Batch(run, now);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("previousLifecycle", run.lifecycle().name());
            put(payload, "reason", why);
            pendingPauseRequester(tx, runId, payload);
            payload.put("fence", fence);
            payload.put("runtimeMillis", runtime);
            payload.put("inDoubtEffects", pendingEffects(tx, runId));
            payload.put("terminalUsage", usage(usage));
            batch.add(RunEventType.RUN_CANCELLED, payload, actor, null, fence, null);
            RunRecord updated = run.with(summary(run.summary(), RunLifecycle.CANCELLED, null, RunProjection.CANCELLED_CODE,
                    batch, run.summary().checkpointSequence(), fence, usage, run.summary().startedAt(), now),
                    control(run, null, null, null, now));
            tx.updateRun(run, updated);
            tx.appendEvents(batch.events);
            ended(tx, runId, now);
            return updated;
        });
    }

    /**
     * Fails a non-terminal run without a lease, for example a run that no node can interpret or whose dependency
     * failed definitively while it was blocked. An active run's lease is revoked by advancing the fence, exactly as
     * cancellation does; in-doubt effects stay pending and explicit.
     */
    public RunRecord failRun(WorkspaceId workspace, UUID runId, String code, String actor, String message) {
        RunEventCodec.identity(actor, "actor");
        if (code == null || !CODE.matcher(code).matches()) {
            throw new RunEngineException("INVALID_EVENT", "a failure code is 1 to 64 characters of [A-Z0-9_]");
        }
        String text = bounded(message, "message");
        return call("run.failRun", workspace, tx -> {
            RunRecord run = lock(tx, runId);
            if (run.lifecycle().terminal()) throw terminal(run);
            Instant now = tx.now();
            boolean active = run.lifecycle() == RunLifecycle.ACTIVE;
            long runtime = active ? elapsed(run.control().activeSince(), earliest(now, run.control().leaseUntil())) : 0;
            long fence = active ? run.summary().leaseFence() + 1 : run.summary().leaseFence();
            RunUsage usage = run.summary().usage().plusRuntime(runtime);
            Batch batch = new Batch(run, now);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("code", code);
            put(payload, "message", text);
            payload.put("previousLifecycle", run.lifecycle().name());
            pendingPauseRequester(tx, runId, payload);
            payload.put("fence", fence);
            payload.put("runtimeMillis", runtime);
            payload.put("inDoubtEffects", pendingEffects(tx, runId));
            payload.put("terminalUsage", usage(usage));
            batch.add(RunEventType.RUN_FAILED, payload, actor, null, fence, null);
            RunRecord updated = run.with(summary(run.summary(), RunLifecycle.FAILED, null, code, batch,
                    run.summary().checkpointSequence(), fence, usage, run.summary().startedAt(), now),
                    control(run, null, null, null, now));
            tx.updateRun(run, updated);
            tx.appendEvents(batch.events);
            ended(tx, runId, now);
            return updated;
        });
    }

    /**
     * Resolves an in-doubt effect of a terminal run as {@code ABANDONED} with an audited reason. This is
     * post-terminal evidence: it never re-executes or claims to undo the effect.
     */
    public RunRecord resolveEffect(WorkspaceId workspace, UUID runId, String effectKey, String actor, String resolution) {
        RunEventCodec.identity(actor, "actor");
        effectKey(effectKey);
        String reason = bounded(Objects.requireNonNull(resolution, "resolution"), "resolution");
        return call("run.resolveEffect", workspace, tx -> {
            RunRecord run = lock(tx, runId);
            if (!run.lifecycle().terminal()) {
                throw new RunEngineException("EFFECT_RESOLUTION_REQUIRES_TERMINAL_RUN",
                        "only a terminal run's in-doubt effects are resolved; cancel the run first");
            }
            RunEffect effect = tx.findEffect(runId, effectKey).orElseThrow(() -> new RunEngineException("NOT_FOUND",
                    "effect not found"));
            if (effect.state() != RunEffect.State.PENDING) {
                throw new RunEngineException("EFFECT_NOT_IN_DOUBT", "the effect is already " + effect.state());
            }
            Instant now = tx.now();
            Batch batch = new Batch(run, now);
            batch.add(RunEventType.OPERATION_RESOLVED, Map.of("stepId", effectKey, "effectKey", effectKey,
                    "resolution", "ABANDONED", "reason", reason), actor, null, run.summary().leaseFence(), null);
            RunRecord updated = run.with(summary(run.summary(), run.lifecycle(), null, run.summary().terminalCode(), batch,
                    run.summary().checkpointSequence(), run.summary().leaseFence(), run.summary().usage(),
                    run.summary().startedAt(), run.summary().completedAt()), control(run, null, null, null, now));
            tx.updateRun(run, updated);
            tx.appendEvents(batch.events);
            tx.updateEffect(effect, effect.transition(RunEffect.State.ABANDONED, effect.attempts(), null, reason, null,
                    effect.leaseFence(), now));
            closeOperation(tx, runId, effect.id(), now);
            return updated;
        });
    }

    // Claims and lease-holder operations

    /**
     * Claims the oldest claimable run in {@code scope}: a queued run, or an active run whose lease expired in
     * database time (recovery). Before taking ownership the engine verifies the history, rebuilds the summary, and
     * reads the pinned manifest; a run this node cannot interpret raises {@link RunClaimRefusedException} and
     * nothing changes.
     */
    public Optional<ClaimedRun> claimNext(String owner, ClaimScope scope, Duration leaseDuration) {
        return claimNext(owner, scope, leaseDuration, candidate -> { });
    }

    /**
     * A claim attempt that failed after selecting {@code runId}; {@code observed} is the run state it read, or
     * {@code null} when it failed before the run could be read and decoded.
     */
    public record FailedClaim(UUID runId, com.dvarahq.agentworks.domain.run.ClaimQuarantine.Observed observed) {}

    /**
     * As {@link #claimNext(String, ClaimScope, Duration)}, and reports the selected run when the attempt fails after
     * selecting it: while the run is read and decoded (#179), claimed, or committed. A failure before any run was
     * selected reports nothing. {@code null} is reported at the start of every retry attempt, so a failure is never
     * attributed to a run an earlier attempt selected. The claim honors the claim quarantines of this node's
     * compatibility identity only (#181).
     */
    public Optional<ClaimedRun> claimNext(String owner, ClaimScope scope, Duration leaseDuration,
            java.util.function.Consumer<FailedClaim> failedCandidate) {
        RunEventCodec.identity(owner, "owner");
        Duration duration = lease(leaseDuration);
        Objects.requireNonNull(failedCandidate, "failedCandidate");
        // Every claim honors the quarantines of this node's compatibility identity (#181, #185, PR #199 review): the
        // engine names its own, and refuses a scope that names another rather than silently replacing it.
        if (scope.namesCompatibility() && !scope.compatibility().equals(compatibility)) {
            throw new IllegalArgumentException("the claim scope names compatibility identity " + scope.compatibility()
                    + ", but this engine's is " + compatibility);
        }
        ClaimScope compatible = scope.compatibleWith(compatibility);
        try {
            Optional<ClaimedRun> claimed = retry.execute("run.claim", () -> {
                failedCandidate.accept(null);
                java.util.concurrent.atomic.AtomicReference<UUID> selected = new java.util.concurrent.atomic.AtomicReference<>();
                java.util.concurrent.atomic.AtomicReference<com.dvarahq.agentworks.domain.run.ClaimQuarantine.Observed>
                        observed = new java.util.concurrent.atomic.AtomicReference<>();
                try {
                    return store.claimNext(compatible, selected::set, (run, tx) -> {
                        selected.set(run.id());
                        observed.set(new com.dvarahq.agentworks.domain.run.ClaimQuarantine.Observed(
                                run.summary().eventSequence(), run.summary().leaseFence()));
                        return claim(tx, run, owner, duration, compatible);
                    });
                } catch (RunClaimRefusedException refused) {
                    throw refused;
                } catch (RuntimeException failed) {
                    if (selected.get() != null) failedCandidate.accept(new FailedClaim(selected.get(), observed.get()));
                    throw failed;
                }
            });
            claimed.filter(ClaimedRun::recovered).ifPresent(run -> {
                try {
                    recoveryObserver.recovered(run.recoveryDelay());
                } catch (RuntimeException telemetryFailure) {
                    LOG.log(System.Logger.Level.WARNING, "run recovery observer failed", telemetryFailure);
                }
            });
            return claimed;
        } catch (RunAuthorityException refused) {
            throw typed(refused);
        }
    }

    /**
     * Quarantines a run whose claim this node cannot complete (#181), durably, for every node of the request's
     * compatibility identity (normally {@link #compatibility()}): their claim query skips it until the quarantine ends,
     * or, when indefinite, until an operator releases it; nodes of other identities are unaffected. Fenced by the run's
     * row lock: empty when the run is no longer claimable or has moved on from the state the refused claim observed.
     */
    public Optional<com.dvarahq.agentworks.domain.run.ClaimQuarantine> quarantine(UUID runId,
            com.dvarahq.agentworks.domain.run.ClaimQuarantine.Request request) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(request, "request");
        try {
            return retry.execute("run.quarantine", () -> store.quarantine(runId, request));
        } catch (RunAuthorityException refused) {
            throw typed(refused);
        }
    }

    /** Whether the engine's store keeps the claim quarantine (#181); a worker refuses an engine whose store does not. */
    public boolean keepsClaimQuarantine() {
        return store.keepsClaimQuarantine();
    }

    /**
     * Extends a live lease in database time; an expired lease is never revived. When the open active period exceeds
     * the pinned runtime limit, the heartbeat instead records LIMIT_EXCEEDED, fails the run, releases the lease, and
     * throws {@code LIMIT_EXCEEDED}.
     */
    public RunLease heartbeat(RunLease lease, Duration leaseDuration) {
        return heartbeat(lease, leaseDuration, Duration.ZERO);
    }

    /**
     * As {@link #heartbeat(RunLease, Duration)}, but while the run has a PENDING effect the runtime limit is enforced
     * only once the open active period exceeds it by more than {@code runtimeGrace} (at most
     * {@link #MAX_RUNTIME_GRACE}). A worker passes a grace while a modeled effect is in flight, so the heartbeat does
     * not fail the run under the effect and orphan its PENDING intent; the effect's outcome is recorded first, and the
     * next heartbeat or write enforces the limit (#175). The engine checks the pending effect itself, so a grace never
     * relaxes the limit of a run without one.
     */
    public RunLease heartbeat(RunLease lease, Duration leaseDuration, Duration runtimeGrace) {
        return renew(lease, leaseDuration, runtimeGrace, false).lease();
    }

    /**
     * As {@link #heartbeat(RunLease, Duration, Duration)}. With {@code observePause} it also reports whether an
     * operator's pause request stands (#194), read in the same transaction, so every heartbeat refreshes the worker's
     * view of it. A worker observes only for an executor that may be paused mid-exchange
     * ({@link #resumableMidExchange(RunManifest)}); for any other, a request is never honored, and a heartbeat reads
     * nothing more than the run row it locks.
     */
    public Renewal renew(RunLease lease, Duration leaseDuration, Duration runtimeGrace, boolean observePause) {
        Duration duration = lease(leaseDuration);
        Objects.requireNonNull(runtimeGrace, "runtimeGrace");
        long graceMillis = runtimeGrace.isNegative() ? 0
                : (runtimeGrace.compareTo(MAX_RUNTIME_GRACE) > 0 ? MAX_RUNTIME_GRACE : runtimeGrace).toMillis();
        Renewal renewed = call("run.heartbeat", lease.workspace(), tx -> {
            RunRecord run = lock(tx, lease.runId());
            Instant now = tx.now();
            requireLive(run, lease, now, false);
            // A handler that is stuck but still heartbeating must still hit its runtime limit: the open active period
            // is charged and, when it exceeds the pinned limit, the run fails and the worker is fenced out.
            long openRuntime = elapsed(run.control().activeSince(), now);
            long grace = graceMillis > 0 && !pendingEffects(tx, run.id()).isEmpty() ? graceMillis : 0;
            long charged = Math.max(0, openRuntime - grace);
            if (!run.pins().limits().exceeded(run.summary().usage().plusRuntime(charged)).isEmpty()) {
                RunLease current = new RunLease(lease.workspace(), lease.runId(), lease.cohortId(), lease.owner(),
                        lease.fence(), run.control().rowVersion(), run.control().leaseUntil());
                finishWrite(tx, run, current, new Batch(run, now), RunUsage.ZERO, run.summary().checkpointSequence(), now);
                return null;
            }
            Instant until = now.plus(duration);
            RunControl control = new RunControl(run.control().rowVersion(), lease.owner(), until,
                    run.control().activeSince(), now);
            tx.updateRun(run, run.with(run.summary(), control));
            return new Renewal(new RunLease(lease.workspace(), lease.runId(), lease.cohortId(), lease.owner(),
                    lease.fence(), run.control().rowVersion(), until),
                    observePause && tx.pauseRequest(run.id()).isPresent());
        });
        if (renewed == null) {
            throw limitExceeded();
        }
        return renewed;
    }

    /** Appends worker-authored events, accumulating their usage deltas and enforcing the pinned limits. */
    public WriteResult append(RunLease lease, List<EventDraft> drafts) {
        Objects.requireNonNull(drafts, "drafts");
        if (drafts.isEmpty()) throw new RunEngineException("INVALID_EVENT", "append at least one event");
        for (EventDraft draft : drafts) {
            if (draft.type().authorship() != RunEventType.Authorship.WORKER) {
                throw new RunEngineException("INVALID_EVENT", draft.type() + " is written only by the run engine");
            }
            RunEventCodec.spanId(draft.spanId());
            if (draft.payload() != null) {
                var keys = codec.object(codec.canonicalPayload(draft.payload())).propertyNames();
                for (String reserved : RESERVED_WORKER_KEYS) {
                    if (keys.contains(reserved)) {
                        throw new RunEngineException("INVALID_EVENT", reserved + " is reserved for engine events");
                    }
                }
            }
        }
        return call("run.append", lease.workspace(), tx -> {
            RunRecord run = owned(tx, lease);
            Instant now = tx.now();
            Batch batch = new Batch(run, now);
            RunUsage delta = RunUsage.ZERO;
            for (EventDraft draft : drafts) {
                RunEvent event = batch.add(draft.type(), draft.payload(), worker(lease), lease.owner(), lease.fence(),
                        draft.spanId());
                delta = delta.plus(RunProjection.usageDelta(codec.object(event.payloadJson())));
            }
            return finishWrite(tx, run, lease, batch, delta, run.summary().checkpointSequence(), now);
        });
    }

    /**
     * Records a checkpoint of {@code state} at the current event position, closing the active runtime period. A
     * checkpoint under a suspended cohort is a permitted safe-boundary write; the result tells the worker to pause.
     */
    public WriteResult checkpoint(RunLease lease, Object state) {
        return call("run.checkpoint", lease.workspace(), tx -> {
            RunRecord run = owned(tx, lease);
            Instant now = tx.now();
            long runtime = elapsed(run.control().activeSince(), now);
            RunUsage closed = run.summary().usage().plusRuntime(runtime);
            if (!run.pins().limits().exceeded(closed).isEmpty()) {
                return finishWrite(tx, run, lease, new Batch(run, now), RunUsage.ZERO, run.summary().checkpointSequence(), now);
            }
            long checkpointSequence = run.summary().checkpointSequence() + 1;
            Batch batch = new Batch(run, now);
            for (String limit : run.pins().limits().warnings(run.summary().usage(), closed)) {
                batch.add(RunEventType.LIMIT_WARNING, Map.of("limit", limit, "observedUsage", usage(closed)), worker(lease),
                        lease.owner(), lease.fence(), null);
            }
            RunEvent created = batch.add(RunEventType.CHECKPOINT_CREATED, Map.of("checkpointSequence", checkpointSequence,
                    "runtimeMillis", runtime), worker(lease), lease.owner(), lease.fence(), null);
            // The envelope is versioned and immutable; it carries the release identity so a later migration (ADR-030,
            // OSS 1.1) can append to a run's history without rewriting any checkpoint.
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("checkpointVersion", CHECKPOINT_VERSION);
            envelope.put("state", state == null ? NullNode.getInstance() : codec.mapper().valueToTree(state));
            envelope.put("eventSequence", created.sequence());
            envelope.put("usage", usage(closed));
            envelope.put("limits", limits(run.pins().limits()));
            envelope.put("pendingEffects", pendingEffects(tx, run.id()));
            envelope.put("pendingWork", tx.openWorkItems(run.id()).stream().map(item -> Map.of(
                    "category", item.category().name(), "subjectId", item.subjectId().toString(),
                    "state", item.state().name(), "createdAt", item.createdAt().toString())).toList());
            RunTransaction.CheckpointResources resources = tx.checkpointResources(run.id());
            envelope.put("artifacts", resources.artifacts());
            envelope.put("sandboxes", resources.sandboxes());
            envelope.put("pins", Map.of("releaseId", run.pins().releaseId().toString(),
                    "deploymentId", run.pins().deploymentId().toString(), "revisionId", run.pins().revisionId().toString(),
                    "cohortId", run.pins().cohortId().toString(), "versionId", run.pins().versionId().toString()));
            String payload = codec.canonicalPayload(envelope);
            tx.appendEvents(batch.events);
            batch.events.clear();
            tx.insertCheckpoint(new RunCheckpoint(UUID.randomUUID(), run.pins().workspaceId(), run.id(),
                    run.pins().cohortId(), checkpointSequence, created.sequence(), payload, RunEventCodec.sha256(payload),
                    lease.owner(), lease.fence(), now));
            Optional<WorkItem> item = tx.openWorkItems(run.id()).stream()
                    .filter(open -> open.category() == WorkItem.Category.CHECKPOINT).findFirst();
            if (item.isPresent()) {
                tx.updateWorkItem(item.get(), item.get().advance(WorkItem.State.ACTIVE, now));
            } else {
                tx.insertWorkItem(new WorkItem(UUID.randomUUID(), run.pins().workspaceId(), run.pins().cohortId(),
                        run.id(), WorkItem.Category.CHECKPOINT, run.id(), WorkItem.State.ACTIVE, now, now, null));
            }
            RunUsage usage = run.summary().usage().plusRuntime(runtime);
            RunRecord updated = run.with(summary(run.summary(), RunLifecycle.ACTIVE, null, null, batch.head, batch.sequence,
                    checkpointSequence, lease.fence(), usage, run.summary().startedAt(), null),
                    new RunControl(run.control().rowVersion() + 1, lease.owner(), run.control().leaseUntil(), now, now));
            tx.updateRun(run, updated);
            RunTransaction.WriteState written = tx.writeState(run.id(), run.pins().cohortId());
            return new WriteResult(next(lease, updated), updated, written.cohortState(), written.pauseRequested());
        });
    }

    /**
     * Executes a modeled external effect at most once per run and effect key. The intent is recorded as pending
     * before invocation; a committed result is replayed without invoking again; a pending intent left by a lost
     * worker is retried only with the same stable external key, so an external system that deduplicates by that key
     * applies the effect once. An invocation that fails for an unknown reason leaves the effect in doubt
     * ({@link EffectInDoubtException}); {@link EffectFailedException} records a definite failure, which is replayed
     * like a result.
     */
    public EffectOutcome executeEffect(RunLease lease, String effectKey, Object request, EffectInvoker invoker) {
        effectKey(effectKey);
        Objects.requireNonNull(invoker, "invoker");
        JsonNode requestNode = request == null ? NullNode.getInstance() : codec.mapper().valueToTree(request);
        String requestDigest = RunEventCodec.sha256(codec.canonicalPayload(Map.of("request", requestNode)));
        String externalKey = RunEventCodec.sha256(lease.workspace().value() + "/" + lease.runId() + "/" + effectKey)
                .substring("sha256:".length());
        record Started(RunLease lease, EffectOutcome replay) {}
        Started started = call("run.effect.start", lease.workspace(), tx -> {
            RunRecord run = owned(tx, lease);
            Instant now = tx.now();
            Optional<RunEffect> existing = tx.findEffect(run.id(), effectKey);
            Batch batch = new Batch(run, now);
            if (existing.isPresent()) {
                RunEffect effect = existing.get();
                if (!effect.requestDigest().equals(requestDigest)) {
                    throw new RunEngineException("EFFECT_KEY_REUSED", "effect " + effectKey + " was recorded for a different request");
                }
                switch (effect.state()) {
                    case COMMITTED -> {
                        return new Started(lease, new EffectOutcome(codec.object(effect.resultJson()).get("result"), null,
                                true, lease, externalKey, tx.pauseRequest(run.id()).isPresent()));
                    }
                    case FAILED -> {
                        return new Started(lease, new EffectOutcome(null, codec.object(effect.resultJson()).path("error")
                                .asString(), true, lease, externalKey, tx.pauseRequest(run.id()).isPresent()));
                    }
                    case ABANDONED -> throw new RunEngineException("EFFECT_ABANDONED", "effect " + effectKey + " was abandoned");
                    case PENDING -> {
                        batch.add(RunEventType.OPERATION_PROGRESS, Map.of("stepId", effectKey, "effectKey", effectKey,
                                "attempt", effect.attempts() + 1, "inDoubtRetry", true), worker(lease), lease.owner(),
                                lease.fence(), null);
                        tx.appendEvents(batch.events);
                        tx.updateEffect(effect, effect.transition(RunEffect.State.PENDING, effect.attempts() + 1, null,
                                null, lease.owner(), lease.fence(), null));
                    }
                }
            } else {
                batch.add(RunEventType.OPERATION_STARTED, Map.of("stepId", effectKey, "effectKey", effectKey,
                        "requestDigest", requestDigest, "externalKey", externalKey), worker(lease), lease.owner(),
                        lease.fence(), null);
                tx.appendEvents(batch.events);
                RunEffect effect = new RunEffect(UUID.randomUUID(), run.pins().workspaceId(), run.id(),
                        run.pins().cohortId(), effectKey, requestDigest, RunEffect.State.PENDING, 1, null, null,
                        lease.owner(), lease.fence(), now, null);
                tx.insertEffect(effect);
                tx.insertWorkItem(new WorkItem(UUID.randomUUID(), run.pins().workspaceId(), run.pins().cohortId(),
                        run.id(), WorkItem.Category.OPERATION, effect.id(), WorkItem.State.PENDING, now, now, null));
            }
            RunRecord updated = advance(run, lease, batch, run.summary().usage(), now);
            tx.updateRun(run, updated);
            return new Started(next(lease, updated), null);
        });
        if (started.replay() != null) return started.replay();
        JsonNode result;
        try {
            result = invoker.invoke(externalKey, requestNode);
        } catch (EffectFailedException failed) {
            String failure = bounded(Objects.requireNonNullElse(failed.getMessage(), "failed"), "failure");
            Renewal after = recordEffectOutcome(started.lease(), effectKey, RunEffect.State.FAILED, Map.of("error", failure));
            if (after == null) throw limitExceeded();
            return new EffectOutcome(null, failure, false, after.lease(), externalKey, after.pauseRequested());
        } catch (Exception unknown) {
            throw new EffectInDoubtException(effectKey, started.lease(), unknown);
        }
        JsonNode committed = result == null ? NullNode.getInstance() : result;
        Renewal after = recordEffectOutcome(started.lease(), effectKey, RunEffect.State.COMMITTED, Map.of("result", committed));
        if (after == null) throw limitExceeded();
        return new EffectOutcome(committed, null, false, after.lease(), externalKey, after.pauseRequested());
    }

    /** ACTIVE → BLOCKED with a durable reason; releases the lease so the run holds no worker. */
    public RunRecord block(RunLease lease, RunBlockReason reason, Object detail) {
        Objects.requireNonNull(reason, "reason");
        return release(lease, RunLifecycle.BLOCKED, reason, payload -> {
            payload.put("reason", reason.name());
            if (detail != null) payload.put("detail", codec.mapper().valueToTree(detail));
        });
    }

    /** Adds the secret-free interaction request event immediately before its atomic ACTIVE -> BLOCKED transition. */
    RunRecord blockInteraction(RunLease lease, RunBlockReason reason, Object detail, EventDraft requested) {
        Objects.requireNonNull(requested, "requested");
        if (reason != RunBlockReason.HUMAN_INTERACTION && reason != RunBlockReason.AUTHORIZATION) {
            throw new RunEngineException("INVALID_EVENT", "an interaction needs an interaction block reason");
        }
        RunEventType expected = reason == RunBlockReason.AUTHORIZATION
                ? RunEventType.AUTHORIZATION_REQUIRED : RunEventType.HUMAN_INTERACTION_REQUESTED;
        if (requested.type() != expected) {
            throw new RunEngineException("INVALID_EVENT", "interaction request event does not match its block reason");
        }
        return release(lease, RunLifecycle.BLOCKED, reason, payload -> {
            payload.put("reason", reason.name());
            if (detail != null) payload.put("detail", codec.mapper().valueToTree(detail));
        }, requested);
    }

    /** Adds the secret-free durable-operation intent immediately before ACTIVE -> BLOCKED releases the lease. */
    RunRecord blockOperation(RunLease lease, UUID operationId, String provider, String operationType,
            String correlationId) {
        Objects.requireNonNull(operationId, "operationId");
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("stepId", operationId.toString());
        intent.put("operationId", operationId.toString());
        intent.put("provider", RunEventCodec.identity(provider, "provider"));
        intent.put("operationType", RunEventCodec.identity(operationType, "operationType"));
        intent.put("correlationId", RunEventCodec.identity(correlationId, "correlationId"));
        return release(lease, RunLifecycle.BLOCKED, RunBlockReason.DURABLE_OPERATION, payload -> {
            payload.put("reason", RunBlockReason.DURABLE_OPERATION.name());
            payload.put("detail", codec.mapper().valueToTree(Map.of(
                    "operationId", operationId.toString(), "correlationId", correlationId)));
        }, EventDraft.of(RunEventType.OPERATION_STARTED, intent));
    }

    /** Records a bounded child intent and releases the parent's worker while that exact child executes. */
    RunRecord blockChild(RunLease lease, UUID relationshipId, UUID childRunId, UUID childReleaseId,
            UUID childDeploymentId, int depth) {
        Objects.requireNonNull(relationshipId, "relationshipId");
        Objects.requireNonNull(childRunId, "childRunId");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("relationshipId", relationshipId.toString());
        detail.put("childRunId", childRunId.toString());
        detail.put("childReleaseId", childReleaseId.toString());
        detail.put("childDeploymentId", childDeploymentId.toString());
        detail.put("depth", depth);
        return release(lease, RunLifecycle.BLOCKED, RunBlockReason.CHILD_RUN, payload -> {
            payload.put("reason", RunBlockReason.CHILD_RUN.name());
            payload.put("detail", codec.mapper().valueToTree(detail));
        });
    }

    /** Resumes a parent exactly once after its recorded child relationship reaches an outcome. */
    RunRecord resumeChild(WorkspaceId workspace, ChildRunRecord child, String actor) {
        Objects.requireNonNull(child, "child");
        if (!child.status().terminal()) throw new RunEngineException("CHILD_NOT_TERMINAL", "child has no outcome");
        RunEventCodec.identity(actor, "actor");
        return call("run.resume-child", workspace, tx -> {
            RunRecord run = exactBlock(tx, child.parentRunId(), RunBlockReason.CHILD_RUN,
                    "relationshipId", child.id().toString());
            if (!run.pins().rootRunId().equals(child.rootRunId())
                    || !run.pins().cohortId().equals(child.parentCohortId())) {
                throw new RunEngineException("CHILD_PINS_MISMATCH", "child relationship does not match its parent");
            }
            Instant now = tx.now();
            Batch batch = new Batch(run, now);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("from", RunLifecycle.BLOCKED.name());
            payload.put("blockReason", RunBlockReason.CHILD_RUN.name());
            payload.put("relationshipId", child.id().toString());
            payload.put("childRunId", child.childRunId().toString());
            payload.put("childStatus", child.status().name());
            payload.put("failurePolicy", child.request().failurePolicy().name());
            put(payload, "terminalCode", child.terminalCode());
            batch.add(RunEventType.RUN_RESUMED, payload, actor, null, run.summary().leaseFence(), null);
            RunRecord updated = run.with(summary(run.summary(), RunLifecycle.QUEUED, null, null, batch,
                    run.summary().checkpointSequence(), run.summary().leaseFence(), run.summary().usage(),
                    run.summary().startedAt(), null), control(run, null, null, null, now));
            tx.updateRun(run, updated); tx.appendEvents(batch.events); return updated;
        });
    }

    /** Records a database-time wakeup and releases the worker; no process timer owns correctness. */
    RunRecord blockWakeup(RunLease lease, UUID wakeupId, Instant dueAt, String correlationId) {
        Map<String, Object> detail = Map.of("wakeupId", wakeupId.toString(), "dueAt", dueAt.toString(),
                "correlationId", correlationId);
        return release(lease, RunLifecycle.BLOCKED, RunBlockReason.SCHEDULED_WAKEUP, payload -> {
            payload.put("reason", RunBlockReason.SCHEDULED_WAKEUP.name());
            payload.put("detail", codec.mapper().valueToTree(detail));
        });
    }

    /** Resumes only the original pinned run after its exact wakeup and inbox delivery committed. */
    RunRecord resumeWakeup(WorkspaceId workspace, ScheduledWakeupRecord wakeup, UUID inboxMessageId, String actor) {
        Objects.requireNonNull(wakeup, "wakeup"); Objects.requireNonNull(inboxMessageId, "inboxMessageId");
        if (wakeup.status() != ScheduledWakeupRecord.Status.DELIVERED) {
            throw new RunEngineException("WAKEUP_NOT_DELIVERED", "wakeup has not been delivered");
        }
        RunEventCodec.identity(actor, "actor");
        return call("run.resume-wakeup", workspace, tx -> {
            RunRecord run = exactBlock(tx, wakeup.runId(), RunBlockReason.SCHEDULED_WAKEUP,
                    "wakeupId", wakeup.id().toString());
            if (!run.pins().releaseId().equals(wakeup.releaseId())
                    || !run.pins().deploymentId().equals(wakeup.deploymentId())
                    || !run.pins().revisionId().equals(wakeup.revisionId())
                    || !run.pins().cohortId().equals(wakeup.cohortId())) {
                throw new RunEngineException("WAKEUP_PINS_MISMATCH", "wakeup pins do not match its run");
            }
            Instant now = tx.now(); Batch batch = new Batch(run, now);
            batch.add(RunEventType.RUN_RESUMED, Map.of("from", RunLifecycle.BLOCKED.name(),
                    "blockReason", RunBlockReason.SCHEDULED_WAKEUP.name(), "wakeupId", wakeup.id().toString(),
                    "messageId", inboxMessageId.toString()), actor, null, run.summary().leaseFence(), null);
            RunRecord updated = run.with(summary(run.summary(), RunLifecycle.QUEUED, null, null, batch,
                    run.summary().checkpointSequence(), run.summary().leaseFence(), run.summary().usage(),
                    run.summary().startedAt(), null), control(run, null, null, null, now));
            tx.updateRun(run, updated); tx.appendEvents(batch.events); return updated;
        });
    }

    private RunRecord exactBlock(RunTransaction tx, UUID runId, RunBlockReason reason, String idField, String id) {
        RunRecord run = lock(tx, runId);
        if (run.lifecycle() != RunLifecycle.BLOCKED || run.summary().blockReason() != reason) {
            throw new RunEngineException("DEPENDENCY_NOT_BLOCKING", "run is not blocked for this dependency");
        }
        List<RunEvent> tail = tx.eventWindow(run.id(), run.summary().eventSequence(), run.summary().eventSequence(), 1);
        if (tail.size() != 1) throw new RunEngineException("CORRUPT_HISTORY", "run blocking event is missing");
        DecodedEvent blocked = codec.decode(tail.getFirst()); JsonNode detail = blocked.payload().get("detail");
        if (blocked.type() != RunEventType.RUN_BLOCKED || detail == null || !detail.isObject()
                || !id.equals(detail.path(idField).asString())) {
            throw new RunEngineException("DEPENDENCY_NOT_BLOCKING", "run did not block for this dependency");
        }
        return run;
    }

    /** ACTIVE → PAUSED; releases the lease. */
    public RunRecord pause(RunLease lease, String reason) {
        String why = bounded(reason, "reason");
        return release(lease, RunLifecycle.PAUSED, null, payload -> put(payload, "reason", why));
    }

    /** ACTIVE → SUCCEEDED; refused while an effect of the run is still in doubt. */
    public RunRecord complete(RunLease lease, Object output) {
        return release(lease, RunLifecycle.SUCCEEDED, null,
                payload -> payload.put("output", output == null ? NullNode.getInstance() : codec.mapper().valueToTree(output)));
    }

    /** ACTIVE → FAILED with a typed code; in-doubt effects stay pending and explicit. */
    public RunRecord fail(RunLease lease, String code, String message) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new RunEngineException("INVALID_EVENT", "a failure code is 1 to 64 characters of [A-Z0-9_]");
        }
        String text = bounded(message, "message");
        return release(lease, RunLifecycle.FAILED, null, payload -> {
            payload.put("code", code);
            put(payload, "message", text);
        });
    }

    // Reads

    public RunRecord run(WorkspaceId workspace, UUID runId) {
        return call("run.read", workspace, tx -> tx.findRun(runId).orElseThrow(() -> notFound(runId)));
    }

    /** Authenticated principal captured on RUN_CREATED, for internal causally linked admission. */
    public Optional<com.dvarahq.agentworks.domain.run.RunInvoker> invoker(WorkspaceId workspace, UUID runId) {
        return store.inTransaction(workspace, tx -> {
            RunRecord run = tx.findRun(runId).orElseThrow(() -> notFound(runId));
            List<RunEvent> first = tx.eventWindow(runId, 1, 1, 1);
            if (first.size() != 1) throw new RunEngineException("CORRUPT_HISTORY", "run creation event is missing");
            return invoker(codec.decode(first.getFirst()).payload().get("invoker"), run);
        });
    }

    /** The run's canonical events, verified and lifted to their current payload versions. */
    public List<DecodedEvent> history(WorkspaceId workspace, UUID runId) {
        return call("run.history", workspace, tx -> {
            tx.findRun(runId).orElseThrow(() -> notFound(runId));
            return codec.decodeHistory(tx.events(runId));
        });
    }

    /**
     * One atomic, verified, read-only replay snapshot. The source run and its immutable pins are the parent
     * provenance of every replay; this method never claims, resumes, re-pins, or writes the source run.
     */
    public record ReplaySource(RunRecord run, RunManifest manifest, JsonNode input,
            Optional<com.dvarahq.agentworks.domain.run.RunInvoker> invoker,
            Optional<com.dvarahq.agentworks.domain.run.PinnedAgentVersion> agentVersion,
            List<DecodedEvent> history, List<RunEffect> effects) {
        public ReplaySource {
            history = List.copyOf(history);
            effects = List.copyOf(effects);
        }
    }

    public ReplaySource replaySource(WorkspaceId workspace, UUID runId) {
        return call("run.replay-source", workspace, tx -> {
            RunRecord run = tx.findRun(runId).orElseThrow(() -> notFound(runId));
            if (run.pins().traceId() == null || run.pins().manifestVersion() == null) {
                throw new RunEngineException("LEGACY_RUN", "runs admitted before the durable engine cannot be replayed");
            }
            List<DecodedEvent> history = codec.decodeHistory(tx.events(runId));
            List<String> divergence = differences(run.summary(), RunProjection.rebuild(history));
            if (!divergence.isEmpty()) {
                throw new RunEngineException("CORRUPT_HISTORY", "the summary diverges from history: " + divergence);
            }
            RunManifest manifest = manifests.read(tx.workspace(), run.pins().releaseId(),
                    tx.releaseManifest(run.pins().releaseId()).orElseThrow(() ->
                            new RunEngineException("INVALID_MANIFEST", "the pinned release is missing")));
            if (manifest.manifestVersion() != run.pins().manifestVersion()
                    || !manifest.versionId().equals(run.pins().versionId())) {
                throw new RunEngineException("INVALID_MANIFEST", "the manifest does not match the run's pins");
            }
            Optional<com.dvarahq.agentworks.domain.run.PinnedAgentVersion> version =
                    tx.agentVersion(run.pins().versionId());
            if (version.isPresent() && !version.get().specDigest().equals(manifest.specDigest())) {
                throw new RunEngineException("INVALID_MANIFEST", "the pinned AgentVersion does not match the manifest digest");
            }
            JsonNode input = history.getFirst().payload().get("input");
            return new ReplaySource(run, manifest, input == null ? NullNode.getInstance() : input,
                    invoker(history.getFirst().payload().get("invoker"), run), version, history, tx.effects(runId));
        });
    }

    /**
     * One verified page of a run's history and the run as it was when the page was read (issue #35).
     *
     * @param run the run at the time of the read; its {@code eventSequence} bounds the page
     * @param events the decoded events after the requested position, in sequence order
     * @param more whether further events up to {@code run.summary().eventSequence()} exist after this page
     */
    public record EventPage(RunRecord run, List<DecodedEvent> events, boolean more) {
        public EventPage {
            Objects.requireNonNull(run, "run");
            events = List.copyOf(events);
        }

        /** The sequence of the last event of the page, or {@code after} when the page is empty. */
        public long lastSequence(long after) {
            return events.isEmpty() ? after : events.getLast().raw().sequence();
        }
    }

    /** The most events one page read returns. */
    public static final int MAX_EVENT_PAGE = 1000;

    /**
     * Reads the events of a run after sequence {@code afterSequence}, at most {@code limit} of them, in one
     * transaction together with the run's summary; the page never extends past the summary's event sequence, so the
     * run and the page describe the same committed state (issue #35).
     *
     * <p>The page is verified as a slice of the digest chain: it must continue gaplessly from {@code afterSequence}
     * and its first event must chain from the stored event at {@code afterSequence}. When {@code anchorDigest} is
     * given (a reader that already holds that event), the stored event must carry exactly that digest, so a reader
     * that pages or streams a history verifies one continuous chain. Payload digests, types, and versions are
     * verified as {@link #history} does. Typed refusals:
     * <ul>
     *   <li>{@code EVENT_SEQUENCE_AHEAD}: {@code afterSequence} is beyond the run's current event sequence;</li>
     *   <li>{@code EVENT_HISTORY_EXPIRED}: the events after {@code afterSequence} are no longer all retained;</li>
     *   <li>{@code CORRUPT_HISTORY}: the stored slice does not verify, or its anchor differs from {@code anchorDigest}.</li>
     * </ul>
     */
    public EventPage eventPage(WorkspaceId workspace, UUID runId, long afterSequence, int limit, String anchorDigest) {
        Objects.requireNonNull(runId, "runId");
        if (afterSequence < 0) throw new RunEngineException("INVALID_EVENT_POSITION", "a position is a sequence of 0 or more");
        if (limit < 1 || limit > MAX_EVENT_PAGE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_EVENT_PAGE);
        }
        if (anchorDigest != null && afterSequence == 0) {
            throw new IllegalArgumentException("the start of a history has no anchor");
        }
        return call("run.eventPage", workspace, tx -> {
            RunRecord run = tx.findRun(runId).orElseThrow(() -> notFound(runId));
            long through = run.summary().eventSequence();
            if (afterSequence > through) {
                throw new RunEngineException("EVENT_SEQUENCE_AHEAD",
                        "the position is beyond the run's current event sequence " + through);
            }
            // The event at the position anchors the page, so it must still be retained too.
            long compacted = tx.compactedThrough(runId);
            if (compacted > 0 && afterSequence <= compacted) {
                throw new RunEngineException("EVENT_HISTORY_EXPIRED",
                        "events after the position are no longer retained; resynchronize from the run");
            }
            if (afterSequence == through) return new EventPage(run, List.of(), false);
            long from = afterSequence == 0 ? 1 : afterSequence;
            // One extra event beyond the page tells whether more follow; the anchor precedes the page.
            int window = limit + 1 + (afterSequence == 0 ? 0 : 1);
            List<RunEvent> stored = tx.eventWindow(runId, from, through, window);
            String previous = null;
            List<RunEvent> slice = stored;
            if (afterSequence > 0) {
                if (stored.isEmpty() || stored.getFirst().sequence() != afterSequence) {
                    throw missing(tx, runId, afterSequence, "event " + afterSequence + " is missing");
                }
                previous = stored.getFirst().eventDigest();
                if (anchorDigest != null && !anchorDigest.equals(previous)) {
                    throw new RunEngineException("CORRUPT_HISTORY",
                            "event " + afterSequence + " differs from the event the reader holds");
                }
                slice = stored.subList(1, stored.size());
            }
            boolean more = slice.size() > limit;
            List<RunEvent> page = more ? slice.subList(0, limit) : slice;
            long expectedLast = Math.min(through, afterSequence + limit);
            if (page.isEmpty() || page.getLast().sequence() != expectedLast
                    || (afterSequence == 0 && page.getFirst().sequence() != 1)) {
                throw missing(tx, runId, afterSequence, "events up to " + expectedLast + " are missing");
            }
            return new EventPage(run, codec.decodeRange(previous, afterSequence + 1, page), more);
        });
    }

    /**
     * The failure for events missing from a page: retention compacting them between this transaction's statements
     * (READ COMMITTED) is an expired position, and anything else is corrupt history.
     */
    private static RunEngineException missing(RunTransaction tx, UUID runId, long afterSequence, String detail) {
        long compacted = tx.compactedThrough(runId);
        if (compacted > 0 && afterSequence <= compacted) {
            return new RunEngineException("EVENT_HISTORY_EXPIRED",
                    "events after the position are no longer retained; resynchronize from the run");
        }
        return new RunEngineException("CORRUPT_HISTORY", detail);
    }

    /** One keyset page of the workspace's runs, newest first (issue #35). */
    public List<RunRecord> runs(WorkspaceId workspace, com.dvarahq.agentworks.domain.run.RunQuery query) {
        Objects.requireNonNull(query, "query");
        return call("run.list", workspace, tx -> tx.listRuns(query));
    }

    /**
     * The current event sequence of each named run of {@code workspace}; unknown runs and runs of other workspaces
     * are absent. Stream delivery polls this once per workspace instead of once per stream (issue #35).
     */
    public Map<UUID, Long> eventSequences(WorkspaceId workspace, java.util.Collection<UUID> runIds) {
        Objects.requireNonNull(runIds, "runIds");
        if (runIds.isEmpty()) return Map.of();
        return call("run.eventSequences", workspace, tx -> Map.copyOf(tx.eventSequences(runIds)));
    }

    public List<AgentStep> steps(WorkspaceId workspace, UUID runId) {
        return StepProjection.project(history(workspace, runId));
    }

    public List<RunEffect> effects(WorkspaceId workspace, UUID runId) {
        return call("run.effects", workspace, tx -> {
            tx.findRun(runId).orElseThrow(() -> notFound(runId));
            return tx.effects(runId);
        });
    }

    /**
     * Verifies history integrity (sequence, digest chain, payload digests, known types and versions, legal
     * transitions) and rebuilds the summary from events for comparison with the persisted row. Nothing is written.
     */
    public IntegrityReport verify(WorkspaceId workspace, UUID runId) {
        return call("run.verify", workspace, tx -> {
            RunRecord run = tx.findRun(runId).orElseThrow(() -> notFound(runId));
            List<String> findings = new ArrayList<>();
            RunSummary rebuilt = null;
            try {
                rebuilt = RunProjection.rebuild(codec.decodeHistory(tx.events(runId)), legacy(run));
                findings.addAll(differences(run.summary(), rebuilt));
            } catch (RunEngineException broken) {
                findings.add(broken.code() + ": " + broken.getMessage());
            }
            Optional<RunCheckpoint> checkpoint = tx.latestCheckpoint(runId);
            if (checkpoint.isPresent()) {
                try {
                    codec.verifyCheckpoint(checkpoint.get());
                } catch (RunEngineException broken) {
                    findings.add(broken.code() + ": " + broken.getMessage());
                }
            }
            return new IntegrityReport(runId, findings.isEmpty(), List.copyOf(findings), run.summary(), rebuilt);
        });
    }

    /**
     * Repairs a divergent summary from intact history. It never appends, edits, or invents an event, never changes
     * the lifecycle, and on PostgreSQL the database refuses any repair that would regress a counter
     * ({@code SUMMARY_REGRESSION}); such divergence escalates to an operator instead.
     */
    public RunRecord repairSummary(WorkspaceId workspace, UUID runId) {
        return call("run.repair", workspace, tx -> {
            RunRecord run = lock(tx, runId);
            RunSummary rebuilt = RunProjection.rebuild(codec.decodeHistory(tx.events(runId)), legacy(run));
            if (rebuilt.equals(run.summary())) return run;
            if (rebuilt.lifecycle() != run.lifecycle() || rebuilt.eventSequence() != run.summary().eventSequence()) {
                throw new RunEngineException("CORRUPT_SUMMARY", "the persisted lifecycle or position diverges from history; "
                        + "operator repair is required and events are never invented");
            }
            RunRecord repaired = run.with(rebuilt, new RunControl(run.control().rowVersion() + 1, run.control().leaseOwner(),
                    run.control().leaseUntil(), run.control().activeSince(), tx.now()));
            tx.updateRun(run, repaired);
            return repaired;
        });
    }

    // Internals

    private ClaimedRun claim(RunTransaction tx, RunRecord run, String owner, Duration duration, ClaimScope scope) {
        List<DecodedEvent> history;
        RunManifest manifest;
        Optional<RunCheckpoint> checkpoint;
        Optional<com.dvarahq.agentworks.domain.run.RunInvoker> invoker;
        Optional<com.dvarahq.agentworks.domain.run.PinnedAgentVersion> agentVersion;
        Map<String, com.dvarahq.agentworks.domain.security.CredentialRef> credentialReferences;
        try {
            if (run.pins().traceId() == null || run.pins().manifestVersion() == null) {
                throw new RunEngineException("LEGACY_RUN", "runs admitted before the durable engine carry no input");
            }
            history = codec.decodeHistory(tx.events(run.id()));
            List<String> divergence = differences(run.summary(), RunProjection.rebuild(history));
            if (!divergence.isEmpty()) {
                throw new RunEngineException("CORRUPT_HISTORY", "the summary diverges from history: " + divergence);
            }
            manifest = manifests.read(tx.workspace(), run.pins().releaseId(), tx.releaseManifest(run.pins().releaseId())
                    .orElseThrow(() -> new RunEngineException("INVALID_MANIFEST", "the pinned release is missing")));
            if (manifest.manifestVersion() != run.pins().manifestVersion()
                    || !manifest.versionId().equals(run.pins().versionId())) {
                throw new RunEngineException("INVALID_MANIFEST", "the manifest does not match the run's pins");
            }
            if (!scope.executes(manifest.executorType())) {
                // Defensive only: the claim query already selects runs by their recorded executor type (#34). A store
                // that did not filter still never lets a node run, pause, or fail a run it has no executor for: the
                // claim is refused unwritten.
                throw new RunEngineException("EXECUTOR_UNAVAILABLE", "this node does not run executor type '"
                        + manifest.executorType() + "'");
            }
            invoker = invoker(history.getFirst().payload().get("invoker"), run);
            agentVersion = tx.agentVersion(run.pins().versionId());
            if (agentVersion.isPresent() && !agentVersion.get().specDigest().equals(manifest.specDigest())) {
                throw new RunEngineException("INVALID_MANIFEST", "the pinned AgentVersion does not match the manifest digest");
            }
            credentialReferences = tx.deploymentConfiguration(run.pins().revisionId())
                    .map(DeploymentCredentialReferences::read).orElseGet(Map::of);
            checkpoint = tx.latestCheckpoint(run.id());
            checkpoint.ifPresent(stored -> {
                codec.verifyCheckpoint(stored);
                int version = codec.object(stored.payloadJson()).path("checkpointVersion").intValue(0);
                if (version != CHECKPOINT_VERSION) {
                    throw new RunEngineException("UNSUPPORTED_CHECKPOINT_VERSION",
                            "checkpoint version " + version + " is not supported by this node");
                }
            });
        } catch (RunEngineException refused) {
            throw new RunClaimRefusedException(run.id(), refused, new com.dvarahq.agentworks.domain.run.ClaimQuarantine
                    .Observed(run.summary().eventSequence(), run.summary().leaseFence()));
        }
        Instant now = tx.now();
        boolean recovered = run.lifecycle() == RunLifecycle.ACTIVE;
        Duration recoveryDelay = recovered ? Duration.between(run.control().leaseUntil(), now) : null;
        if (recoveryDelay != null && recoveryDelay.isNegative()) recoveryDelay = Duration.ZERO;
        long fence = run.summary().leaseFence() + 1;
        Batch batch = new Batch(run, now);
        long runtime = 0;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("worker", owner);
        payload.put("fence", fence);
        if (recovered) {
            runtime = elapsed(run.control().activeSince(), run.control().leaseUntil());
            payload.put("previousOwner", run.control().leaseOwner());
            payload.put("previousFence", run.summary().leaseFence());
            payload.put("runtimeMillis", runtime);
        }
        RunUsage usage = run.summary().usage().plusRuntime(runtime);
        Instant until = now.plus(duration);
        RunLease lease = new RunLease(tx.workspace(), run.id(), run.pins().cohortId(), owner, fence,
                run.control().rowVersion() + 1, until);
        batch.add(recovered ? RunEventType.RUN_RECOVERED : RunEventType.RUN_ACTIVATED, payload, worker(lease), owner,
                fence, null);
        checkpoint.ifPresent(restored -> batch.add(RunEventType.CHECKPOINT_RESTORED, Map.of("checkpointSequence",
                restored.sequence(), "eventSequence", restored.eventSequence()), worker(lease), owner, fence, null));
        RunRecord updated = run.with(summary(run.summary(), RunLifecycle.ACTIVE, null, null, batch.head, batch.sequence,
                run.summary().checkpointSequence(), fence, usage,
                run.summary().startedAt() == null ? now : run.summary().startedAt(), null),
                new RunControl(run.control().rowVersion() + 1, owner, until, now, now));
        tx.updateRun(run, updated);
        tx.appendEvents(batch.events);
        closeItems(tx, run.id(), Set.of(WorkItem.Category.CLAIM), now);
        tx.insertWorkItem(new WorkItem(UUID.randomUUID(), run.pins().workspaceId(), run.pins().cohortId(), run.id(),
                WorkItem.Category.CLAIM, run.id(), WorkItem.State.ACTIVE, now, now, null));
        JsonNode input = history.getFirst().payload().get("input");
        Optional<RestoredCheckpoint> restored = checkpoint.map(stored -> new RestoredCheckpoint(stored.sequence(),
                stored.eventSequence(), codec.object(stored.payloadJson()).get("state")));
        RunTransaction.WriteState written = tx.writeState(run.id(), run.pins().cohortId());
        return new ClaimedRun(lease, updated, manifest, input == null ? NullNode.getInstance() : input, restored,
                recovered, written.cohortState(), invoker, agentVersion, credentialReferences,
                written.pauseRequested(), recoveryDelay);
    }

    /** A required text field of a recorded object; anything else is malformed. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) throw new IllegalArgumentException(field + " is not text");
        return value.stringValue();
    }

    /** The recorded submitting principal; a malformed record refuses the claim rather than invoking on a guess. */
    private static Optional<com.dvarahq.agentworks.domain.run.RunInvoker> invoker(JsonNode recorded, RunRecord run) {
        if (recorded == null || recorded.isNull()) return Optional.empty();
        try {
            var invoker = new com.dvarahq.agentworks.domain.run.RunInvoker(
                    UUID.fromString(text(recorded, "organizationId")), UUID.fromString(text(recorded, "workspaceId")),
                    UUID.fromString(text(recorded, "subjectId")), UUID.fromString(text(recorded, "credentialId")),
                    text(recorded, "method"));
            if (!invoker.workspaceId().equals(run.pins().workspaceId())) {
                throw new RunEngineException("INVALID_PAYLOAD", "the recorded invoker acts in another workspace");
            }
            return Optional.of(invoker);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new RunEngineException("INVALID_PAYLOAD", "the recorded invoker is malformed", malformed);
        }
    }

    private RunRecord queue(RunTransaction tx, RunRecord run, String actor) {
        if (run.lifecycle() != RunLifecycle.CREATED) {
            if (run.lifecycle().terminal()) throw terminal(run);
            throw illegal(run.lifecycle(), RunLifecycle.QUEUED);
        }
        if (run.pins().traceId() == null || run.pins().manifestVersion() == null) {
            throw new RunEngineException("LEGACY_RUN", "runs admitted before the durable engine carry no input and cannot run");
        }
        Instant now = tx.now();
        Batch batch = new Batch(run, now);
        batch.add(RunEventType.RUN_QUEUED, Map.of(), actor, null, run.summary().leaseFence(), null);
        RunRecord updated = run.with(summary(run.summary(), RunLifecycle.QUEUED, null, null, batch,
                run.summary().checkpointSequence(), run.summary().leaseFence(), run.summary().usage(),
                run.summary().startedAt(), null), control(run, null, null, null, now));
        tx.updateRun(run, updated);
        tx.appendEvents(batch.events);
        return updated;
    }

    /**
     * Ends the active period. Completing, blocking, or pausing a run that is past its pinned limits (for example its
     * runtime, while a heartbeat deferred the limit for an in-flight effect) instead fails it with
     * {@code LIMIT_EXCEEDED} in the same transaction and throws {@code LIMIT_EXCEEDED}: no outcome other than failure
     * is ever recorded after a limit (#175).
     */
    private RunRecord release(RunLease lease, RunLifecycle target, RunBlockReason reason,
            java.util.function.Consumer<Map<String, Object>> details) {
        return release(lease, target, reason, details, null);
    }

    private RunRecord release(RunLease lease, RunLifecycle target, RunBlockReason reason,
            java.util.function.Consumer<Map<String, Object>> details, EventDraft before) {
        RunRecord released = call("run." + target.name().toLowerCase(java.util.Locale.ROOT), lease.workspace(),
                tx -> releaseIn(tx, owned(tx, lease), lease, target, reason, details, before));
        if (target != RunLifecycle.FAILED && released.lifecycle() == RunLifecycle.FAILED) throw limitExceeded();
        return released;
    }

    private RunRecord releaseIn(RunTransaction tx, RunRecord run, RunLease lease, RunLifecycle target,
            RunBlockReason reason, java.util.function.Consumer<Map<String, Object>> details, EventDraft before) {
        Instant now = tx.now();
        List<String> inDoubt = pendingEffects(tx, run.id());
        long runtime = elapsed(run.control().activeSince(), now);
        RunUsage usage = run.summary().usage().plusRuntime(runtime);
        List<String> exceeded = run.pins().limits().exceeded(usage);
        if (target != RunLifecycle.FAILED && !exceeded.isEmpty()) {
            Batch batch = new Batch(run, now);
            RunRecord failed = limitFailure(tx, run, lease, batch, exceeded, usage, runtime, inDoubt,
                    run.summary().checkpointSequence(), now);
            tx.appendEvents(batch.events);
            tx.updateRun(run, failed);
            ended(tx, run.id(), now);
            return failed;
        }
        if (target == RunLifecycle.SUCCEEDED && !inDoubt.isEmpty()) {
            throw new RunEngineException("EFFECT_IN_DOUBT", "effects " + inDoubt + " are still pending");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        details.accept(payload);
        if (target == RunLifecycle.PAUSED) {
            // An operator's pending request is consumed by this pause, whatever its reason, and the event names the
            // requesting actor (#183): exactly one RUN_PAUSED answers it. The operator reason is reserved for it.
            Optional<com.dvarahq.agentworks.domain.run.RunPauseRequest> request = tx.takePauseRequest(run.id());
            if (request.isPresent()) {
                payload.put("requestedBy", request.get().requestedBy());
                payload.put("requestedAt", request.get().requestedAt().toString());
                put(payload, "detail", request.get().reason());
            } else if (OPERATOR_PAUSE.equals(payload.get("reason"))) {
                throw new RunEngineException("PAUSE_NOT_REQUESTED",
                        OPERATOR_PAUSE + " pauses only a run with a pending request; it was withdrawn or never made");
            }
            payload.put("inDoubtEffects", inDoubt);
        } else {
            // The run blocked or ended before its next boundary: that answers the request, which ends here and is
            // named in the event (#183). A blocked run holds no worker; pause it again once it resumes.
            pendingPauseRequester(tx, run.id(), payload, target.terminal() ? "ended" : "blocked");
        }
        payload.put("runtimeMillis", runtime);
        if (target.terminal()) {
            payload.put("inDoubtEffects", inDoubt);
            payload.put("terminalUsage", usage(usage));
        }
        Batch batch = new Batch(run, now);
        if (before != null) {
            batch.add(before.type(), before.payload(), worker(lease), lease.owner(), lease.fence(), before.spanId());
        }
        batch.add(transitionEvent(run.lifecycle(), target), payload, worker(lease), lease.owner(), lease.fence(), null);
        tx.appendEvents(batch.events);
        String code = target == RunLifecycle.FAILED ? (String) payload.get("code") : null;
        RunRecord updated = run.with(summary(run.summary(), target, reason, code, batch,
                run.summary().checkpointSequence(), lease.fence(), usage, run.summary().startedAt(),
                target.terminal() ? now : null), control(run, null, null, null, now));
        tx.updateRun(run, updated);
        if (target.terminal()) {
            ended(tx, run.id(), now);
        } else {
            closeItems(tx, run.id(), Set.of(WorkItem.Category.CLAIM), now);
        }
        return updated;
    }

    /**
     * Pauses the run at this safe boundary if, and only if, an operator's request is still pending and no effect is
     * in doubt (#183), atomically under the run lock: a request that a resume withdrew, or a run with an in-doubt
     * effect, is left active and the holder continues. Empty when nothing changed; a run past its limits is failed
     * instead ({@code LIMIT_EXCEEDED}), exactly as {@link #pause} would.
     */
    public Optional<RunRecord> pauseIfRequested(RunLease lease) {
        return Optional.ofNullable(pauseAtBoundary(lease).paused());
    }

    /**
     * As {@link #pauseIfRequested}, and, when nothing changed, whether the request still stands (#194). It stands while
     * an effect is in doubt, so the holder keeps checking at its later boundaries instead of forgetting it.
     */
    public BoundaryPause pauseAtBoundary(RunLease lease) {
        return pauseAtBoundary(lease, null);
    }

    /**
     * As {@link #pauseAtBoundary(RunLease)}, for a holder that already knows whether the run's pinned executor may be
     * paused mid-exchange ({@link #resumableMidExchange(RunManifest)} of the manifest it claimed with, #194): the
     * boundary then reads no release manifest under the run lock. {@code null} reads it.
     */
    public BoundaryPause pauseAtBoundary(RunLease lease, Boolean resumableMidExchange) {
        BoundaryPause answer = call("run.pauseIfRequested", lease.workspace(), tx -> {
            RunRecord run = owned(tx, lease);
            if (tx.pauseRequest(run.id()).isEmpty()) return new BoundaryPause(null, false);
            if (!pendingEffects(tx, run.id()).isEmpty()) return new BoundaryPause(null, true);
            if (!(resumableMidExchange != null ? resumableMidExchange : resumableMidExchange(tx, run))) {
                // A request recorded by a node that allowed it, for an executor that cannot resume mid-exchange
                // safely, is cleared rather than honored (#183); the run continues.
                tx.takePauseRequest(run.id()).ifPresent(request -> LOG.log(System.Logger.Level.WARNING, "the pause "
                        + "of run {0} requested by {1} is cleared: its executor cannot resume it mid-exchange without "
                        + "repeating work", run.id(), request.requestedBy()));
                return new BoundaryPause(null, false);
            }
            return new BoundaryPause(releaseIn(tx, run, lease, RunLifecycle.PAUSED, null,
                    payload -> payload.put("reason", OPERATOR_PAUSE), null), false);
        });
        if (answer.pausedNow() && answer.paused().lifecycle() == RunLifecycle.FAILED) throw limitExceeded();
        return answer;
    }

    /**
     * Completes a lease-holder write: evaluates limits over the new usage and, when exceeded, records
     * LIMIT_EXCEEDED and fails the run in the same transaction. Events are appended before the summary update.
     */
    private WriteResult finishWrite(RunTransaction tx, RunRecord run, RunLease lease, Batch batch, RunUsage delta,
            long checkpointSequence, Instant now) {
        RunLimits limits = run.pins().limits();
        RunUsage before = run.summary().usage();
        RunUsage after = before.plus(delta);
        for (String limit : limits.warnings(before, after)) {
            batch.add(RunEventType.LIMIT_WARNING, Map.of("limit", limit, "observedUsage", usage(after)), worker(lease),
                    lease.owner(), lease.fence(), null);
        }
        long openRuntime = elapsed(run.control().activeSince(), now);
        List<String> exceeded = limits.exceeded(after.plusRuntime(openRuntime));
        if (exceeded.isEmpty()) {
            tx.appendEvents(batch.events);
            RunRecord updated = advance(run, lease, batch, after, now);
            tx.updateRun(run, updated);
            RunTransaction.WriteState written = tx.writeState(run.id(), run.pins().cohortId());
            return new WriteResult(next(lease, updated), updated, written.cohortState(), written.pauseRequested());
        }
        RunUsage terminal = after.plusRuntime(openRuntime);
        RunRecord updated = limitFailure(tx, run, lease, batch, exceeded, terminal, openRuntime,
                pendingEffects(tx, run.id()),
                checkpointSequence, now);
        tx.appendEvents(batch.events);
        tx.updateRun(run, updated);
        ended(tx, run.id(), now);
        return new WriteResult(null, updated, tx.cohortState(run.pins().cohortId()), false);
    }

    /**
     * Adds {@code LIMIT_EXCEEDED} and {@code RUN_FAILED} to {@code batch} and returns the failed record. The caller
     * appends the batch, writes the record, and closes the run's claim and checkpoint items.
     */
    private RunRecord limitFailure(RunTransaction tx, RunRecord run, RunLease lease, Batch batch, List<String> exceeded,
            RunUsage terminal, long openRuntime, List<String> inDoubt, long checkpointSequence, Instant now) {
        batch.add(RunEventType.LIMIT_EXCEEDED, Map.of("limits", exceeded, "observedUsage", usage(terminal)), worker(lease),
                lease.owner(), lease.fence(), null);
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("code", "LIMIT_EXCEEDED");
        failure.put("message", "run limits exceeded: " + exceeded);
        failure.put("runtimeMillis", openRuntime);
        failure.put("inDoubtEffects", inDoubt);
        failure.put("terminalUsage", usage(terminal));
        pendingPauseRequester(tx, run.id(), failure);
        batch.add(RunEventType.RUN_FAILED, failure, worker(lease), lease.owner(), lease.fence(), null);
        return run.with(summary(run.summary(), RunLifecycle.FAILED, null, "LIMIT_EXCEEDED", batch,
                checkpointSequence, lease.fence(), terminal, run.summary().startedAt(), now),
                control(run, null, null, null, now));
    }

    /** The one LIMIT_EXCEEDED failure every limit check throws. */
    static RunEngineException limitExceeded() {
        return new RunEngineException("LIMIT_EXCEEDED", "the run exceeded its limits and was failed");
    }

    /** Records an effect's outcome; returns {@code null} when the run was past its limits and was failed. */
    /** The holder's next token, and whether a pause request stands; null when a limit failed the run. */
    private Renewal recordEffectOutcome(RunLease lease, String effectKey, RunEffect.State outcome, Map<String, Object> body) {
        return call("run.effect.finish", lease.workspace(), tx -> {
            RunRecord run = owned(tx, lease);
            Instant now = tx.now();
            RunEffect effect = tx.findEffect(run.id(), effectKey).orElseThrow();
            if (effect.state() != RunEffect.State.PENDING) {
                throw new RunEngineException("EFFECT_NOT_IN_DOUBT", "effect " + effectKey + " is already " + effect.state());
            }
            String result = codec.canonicalPayload(body);
            Batch batch = new Batch(run, now);
            batch.add(outcome == RunEffect.State.COMMITTED ? RunEventType.OPERATION_COMPLETED : RunEventType.OPERATION_FAILED,
                    Map.of("stepId", effectKey, "effectKey", effectKey, "resultDigest", RunEventCodec.sha256(result)),
                    worker(lease), lease.owner(), lease.fence(), null);
            // The effect's truthful outcome is always recorded. If the run is past its limits (a heartbeat deferred
            // them while the effect was in flight), the run fails in the same transaction, right after the outcome.
            long openRuntime = elapsed(run.control().activeSince(), now);
            RunUsage terminal = run.summary().usage().plusRuntime(openRuntime);
            List<String> exceeded = run.pins().limits().exceeded(terminal);
            RunRecord updated = null;
            if (!exceeded.isEmpty()) {
                List<String> inDoubt = new ArrayList<>(pendingEffects(tx, run.id()));
                inDoubt.remove(effectKey);
                updated = limitFailure(tx, run, lease, batch, exceeded, terminal, openRuntime, inDoubt,
                        run.summary().checkpointSequence(), now);
            }
            tx.appendEvents(batch.events);
            tx.updateEffect(effect, effect.transition(outcome, effect.attempts(), result, null, lease.owner(),
                    lease.fence(), now));
            closeOperation(tx, run.id(), effect.id(), now);
            if (updated != null) {
                tx.updateRun(run, updated);
                ended(tx, run.id(), now);
                return null;
            }
            updated = advance(run, lease, batch, run.summary().usage(), now);
            tx.updateRun(run, updated);
            return new Renewal(next(lease, updated), tx.pauseRequest(run.id()).isPresent());
        });
    }

    private RunRecord advance(RunRecord run, RunLease lease, Batch batch, RunUsage usage, Instant now) {
        return run.with(summary(run.summary(), RunLifecycle.ACTIVE, null, null, batch.head, batch.sequence,
                run.summary().checkpointSequence(), lease.fence(), usage, run.summary().startedAt(), null),
                new RunControl(run.control().rowVersion() + 1, lease.owner(), run.control().leaseUntil(),
                        run.control().activeSince(), now));
    }

    private RunRecord owned(RunTransaction tx, RunLease lease) {
        RunRecord run = lock(tx, lease.runId());
        requireLive(run, lease, tx.now(), true);
        return run;
    }

    /** The lease is live only in database time; a newer write by the same holder supersedes an older token. */
    private static void requireLive(RunRecord run, RunLease lease, Instant now, boolean ordered) {
        if (run.lifecycle() != RunLifecycle.ACTIVE || !lease.owner().equals(run.control().leaseOwner())
                || run.summary().leaseFence() != lease.fence() || run.control().leaseUntil() == null
                || !run.control().leaseUntil().isAfter(now)) {
            throw new RunEngineException("STALE_LEASE", "the lease on run " + run.id() + " is no longer held");
        }
        if (ordered && run.control().rowVersion() != lease.rowVersion()) {
            throw new RunEngineException("STALE_LEASE", "a newer write by this lease holder superseded the token");
        }
    }

    private RunRecord lock(RunTransaction tx, UUID runId) {
        return tx.lockRun(Objects.requireNonNull(runId, "runId")).orElseThrow(() -> notFound(runId));
    }

    private <T> T call(String operation, WorkspaceId workspace, Function<RunTransaction, T> work) {
        Objects.requireNonNull(workspace, "workspace");
        try {
            return retry.execute(operation, () -> store.inTransaction(workspace, work));
        } catch (RunAuthorityException refused) {
            throw typed(refused);
        } catch (RunStoreConflictException conflict) {
            throw new RunEngineException("STALE_LEASE", "the run changed concurrently", conflict);
        }
    }

    private static RunEngineException typed(RunAuthorityException refused) {
        return new RunEngineException(refused.code(), refused.getMessage(), refused);
    }

    /** A run admitted before issue #32 carries no trace, manifest version, input, or RUN_CREATED event. */
    private static boolean legacy(RunRecord run) {
        return run.pins().traceId() == null;
    }

    private static List<String> pendingEffects(RunTransaction tx, UUID runId) {
        return tx.pendingEffectKeys(runId);
    }

    /**
     * A run ended: its claim and checkpoint items close. Every path that ends a run first removes its pending pause
     * request and names it in the ending event ({@link #pendingPauseRequester}, #183).
     */
    private static void ended(RunTransaction tx, UUID runId, Instant now) {
        closeItems(tx, runId, Set.of(WorkItem.Category.CLAIM, WorkItem.Category.CHECKPOINT), now);
    }

    /** Removes a pending pause request as its run ends or blocks, naming it in that event's payload and the log. */
    private static void pendingPauseRequester(RunTransaction tx, UUID runId, Map<String, Object> payload) {
        pendingPauseRequester(tx, runId, payload, "ended");
    }

    private static void pendingPauseRequester(RunTransaction tx, UUID runId, Map<String, Object> payload,
            String how) {
        tx.takePauseRequest(runId).ifPresent(request -> {
            payload.put("pauseRequestedBy", request.requestedBy());
            LOG.log(System.Logger.Level.INFO, "run {0} {1} before its next safe boundary; the pause requested by {2} "
                    + "at {3} ends with it", runId, how, request.requestedBy(), request.requestedAt());
        });
    }



    private static void closeItems(RunTransaction tx, UUID runId, Set<WorkItem.Category> categories, Instant now) {
        for (WorkItem item : tx.openWorkItems(runId)) {
            if (categories.contains(item.category())) tx.updateWorkItem(item, item.advance(WorkItem.State.TERMINAL, now));
        }
    }

    private static void closeOperation(RunTransaction tx, UUID runId, UUID effectId, Instant now) {
        for (WorkItem item : tx.openWorkItems(runId)) {
            if (item.category() == WorkItem.Category.OPERATION && item.subjectId().equals(effectId)) {
                tx.updateWorkItem(item, item.advance(WorkItem.State.TERMINAL, now));
            }
        }
    }

    private static List<String> differences(RunSummary persisted, RunSummary rebuilt) {
        List<String> findings = new ArrayList<>();
        compare(findings, "lifecycle", persisted.lifecycle(), rebuilt.lifecycle());
        compare(findings, "blockReason", persisted.blockReason(), rebuilt.blockReason());
        compare(findings, "terminalCode", persisted.terminalCode(), rebuilt.terminalCode());
        compare(findings, "eventSequence", persisted.eventSequence(), rebuilt.eventSequence());
        compare(findings, "lastEventDigest", persisted.lastEventDigest(), rebuilt.lastEventDigest());
        compare(findings, "checkpointSequence", persisted.checkpointSequence(), rebuilt.checkpointSequence());
        compare(findings, "leaseFence", persisted.leaseFence(), rebuilt.leaseFence());
        compare(findings, "usage", persisted.usage(), rebuilt.usage());
        compare(findings, "startedAt", persisted.startedAt(), rebuilt.startedAt());
        compare(findings, "completedAt", persisted.completedAt(), rebuilt.completedAt());
        return findings;
    }

    private static void compare(List<String> findings, String field, Object persisted, Object rebuilt) {
        if (!Objects.equals(persisted, rebuilt)) findings.add("SUMMARY_MISMATCH: " + field);
    }

    private static RunSummary summary(RunSummary base, RunLifecycle lifecycle, RunBlockReason reason, String code,
            Batch batch, long checkpoint, long fence, RunUsage usage, Instant startedAt, Instant completedAt) {
        return summary(base, lifecycle, reason, code, batch.head, batch.sequence, checkpoint, fence, usage, startedAt,
                completedAt);
    }

    private static RunSummary summary(RunSummary base, RunLifecycle lifecycle, RunBlockReason reason, String code,
            String head, long sequence, long checkpoint, long fence, RunUsage usage, Instant startedAt,
            Instant completedAt) {
        return new RunSummary(lifecycle, reason, code, sequence, head, checkpoint, fence, usage, startedAt, completedAt);
    }

    private static RunControl control(RunRecord run, String owner, Instant until, Instant activeSince, Instant now) {
        return new RunControl(run.control().rowVersion() + 1, owner, until, activeSince, now);
    }

    private static RunLease next(RunLease lease, RunRecord updated) {
        return new RunLease(lease.workspace(), lease.runId(), lease.cohortId(), lease.owner(), lease.fence(),
                updated.control().rowVersion(), updated.control().leaseUntil());
    }

    private static RunEventType transitionEvent(RunLifecycle from, RunLifecycle to) {
        return com.dvarahq.agentworks.domain.run.RunStateMachine.eventFor(from, to)
                .orElseThrow(() -> illegal(from, to));
    }

    private static long elapsed(Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from)) return 0;
        return Duration.between(from, to).toMillis();
    }

    private static Instant earliest(Instant a, Instant b) {
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    /** Validates a lease duration as every lease-taking operation does; {@code null} selects the default. */
    static Duration lease(Duration duration) {
        Duration value = duration == null ? DEFAULT_LEASE : duration;
        if (value.isNegative() || value.isZero() || value.compareTo(MAX_LEASE) > 0) {
            throw new RunEngineException("INVALID_LEASE", "a lease lasts more than zero and at most " + MAX_LEASE);
        }
        return value;
    }

    private static void effectKey(String key) {
        if (key == null || !EFFECT_KEY.matcher(key).matches()) {
            throw new RunEngineException("INVALID_EVENT", "an effect key is 1 to 200 characters of [A-Za-z0-9:._/-]");
        }
    }

    private static String bounded(String value, String name) {
        if (value != null && (value.length() > 1024 || value.indexOf('\u0000') >= 0)) {
            throw new RunEngineException("INVALID_EVENT", name + " is at most 1024 characters");
        }
        return value;
    }

    private static String worker(RunLease lease) {
        return "worker:" + lease.owner();
    }

    private static Map<String, Object> usage(RunUsage usage) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("inputTokens", usage.inputTokens());
        values.put("outputTokens", usage.outputTokens());
        values.put("totalTokens", usage.totalTokens());
        values.put("modelCalls", usage.modelCalls());
        values.put("toolCalls", usage.toolCalls());
        values.put("runtimeMillis", usage.runtimeMillis());
        values.put("estimatedCostUsd", usage.estimatedCostUsd().toPlainString());
        return values;
    }

    private static Map<String, Object> limits(RunLimits limits) {
        Map<String, Object> values = new LinkedHashMap<>();
        put(values, "maxTotalTokens", limits.maxTotalTokens());
        put(values, "maxModelCalls", limits.maxModelCalls());
        put(values, "maxToolCalls", limits.maxToolCalls());
        put(values, "maxRuntimeMillis", limits.maxRuntimeMillis());
        put(values, "maxCostUsd", limits.maxCostUsd() == null ? null : limits.maxCostUsd().toPlainString());
        return values;
    }

    private static void put(Map<String, Object> payload, String name, Object value) {
        if (value != null) payload.put(name, value instanceof UUID || value instanceof Enum<?> ? value.toString() : value);
    }

    private static RunEngineException notFound(UUID runId) {
        return new RunEngineException("NOT_FOUND", "run " + runId + " not found");
    }

    private static RunEngineException terminal(RunRecord run) {
        return new RunEngineException("RUN_TERMINAL", "the run is already " + run.lifecycle());
    }

    private static RunEngineException illegal(RunLifecycle from, RunLifecycle to) {
        return new RunEngineException("ILLEGAL_TRANSITION", from + " cannot transition to " + to);
    }

    private static String random(int bytes) {
        byte[] value = new byte[bytes];
        do {
            RANDOM.nextBytes(value);
        } while (allZero(value));
        return HexFormat.of().formatHex(value);
    }

    private static boolean allZero(byte[] value) {
        for (byte b : value) if (b != 0) return false;
        return true;
    }

    /** Builds a transaction's events with gapless sequences and the digest chain. */
    private final class Batch {
        private final RunPins pins;
        private final Instant now;
        private final List<RunEvent> events = new ArrayList<>();
        private long sequence;
        private String head;

        private Batch(RunRecord run, Instant now) {
            this(run.pins(), run.summary().eventSequence(), run.summary().lastEventDigest(), now);
        }

        private Batch(RunPins pins, long sequence, String head, Instant now) {
            this.pins = pins;
            this.sequence = sequence;
            this.head = head;
            this.now = now;
        }

        private RunEvent add(RunEventType type, Object payload, String actor, String leaseOwner, long fence, String spanId) {
            String canonical = codec.canonicalPayload(payload == null ? Map.of() : payload);
            RunEvent unsigned = new RunEvent(UUID.randomUUID(), pins.workspaceId(), pins.id(), sequence + 1, type.name(),
                    codec.schemas().currentVersion(type), canonical, RunEventCodec.sha256(canonical), RunEventCodec.GENESIS,
                    pins.traceId(), spanId == null ? random(8) : spanId, pins.releaseId(), pins.deploymentId(),
                    pins.revisionId(), pins.cohortId(), actor, leaseOwner, fence, now);
            String digest = RunEventCodec.eventDigest(head, unsigned);
            RunEvent event = new RunEvent(unsigned.id(), unsigned.workspaceId(), unsigned.runId(), unsigned.sequence(),
                    unsigned.type(), unsigned.schemaVersion(), unsigned.payloadJson(), unsigned.payloadDigest(), digest,
                    unsigned.traceId(), unsigned.spanId(), unsigned.releaseId(), unsigned.deploymentId(),
                    unsigned.revisionId(), unsigned.cohortId(), unsigned.actor(), unsigned.leaseOwner(),
                    unsigned.leaseFence(), unsigned.createdAt());
            events.add(event);
            sequence = event.sequence();
            head = digest;
            return event;
        }
    }
}
