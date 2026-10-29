package com.dvarahq.agentworks.runplane.admission.web;

import com.dvarahq.agentworks.api.web.ApiRequestException;
import com.dvarahq.agentworks.api.web.Page;
import com.dvarahq.agentworks.api.web.PageCursor;
import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.domain.run.AgentStep;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.run.RunQuery;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.run.RunUsage;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.identity.ControlActionAudit;
import com.dvarahq.agentworks.identity.web.CurrentPrincipal;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionRejectedException;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionService.AdmittedRun;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionService;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EventPage;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.RunEngineException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Runs API (issue #35; admission from #31 and #32). External requests only submit independent roots through
 * current routing; continuation lineage fields are rejected, never honoured. A submitted root is created and queued
 * in one transaction and answered with {@code 201}, its {@code Location}, and its pins; a replay with the same
 * {@code Idempotency-Key} returns the original run with {@code 200} and {@code Idempotent-Replayed: true}.
 *
 * <p>Runs are listed newest first with keyset pagination and filters; a run's representation carries an
 * {@code ETag} of its event sequence and honours {@code If-None-Match}. Events are read in verified pages of the
 * typed {@link RunEventView} envelope, positioned by event sequence, the same position the reconnectable stream
 * ({@link RunStreamController}) uses. Operators may cancel a run, pause it at its next safe boundary (#183), or resume
 * a paused one; blocked runs resume only through the completion that unblocks them. There is no run migration in 1.0
 * (ADR-030).
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public final class RunAdmissionController {
    /** A W3C traceparent; only its trace id is pinned. */
    /**
     * A traceparent's first four fields. A version above {@code 00} may append fields after a dash, which a parser
     * ignores (W3C Trace Context, forward compatibility, #175); version {@code 00} has exactly four.
     */
    private static final Pattern TRACEPARENT =
            Pattern.compile("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}(-.*)?$");
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RunAdmissionController.class);

    /** Content-free durable compiler decisions for one run; raw context is deliberately absent. */
    public record ContextDebugView(UUID runId, UUID workspaceId, UUID releaseId, UUID deploymentId,
            UUID revisionId, UUID cohortId, List<RunEventView> events) {}

    /** Run pins and summary without the stored idempotency-key and request-fingerprint digests. */
    public record RunView(UUID id, UUID workspaceId, UUID definitionId, UUID versionId, UUID releaseId,
            UUID deploymentId, UUID environmentId, UUID revisionId, UUID cohortId, String admission,
            UUID routingGenerationId, Integer routingTargetOrdinal, UUID parentRunId, UUID rootRunId,
            String continuationKind, String traceId, Integer manifestVersion, String lifecycle, String blockReason,
            String terminalCode, long eventSequence, long checkpointSequence, UsageView usage, Instant startedAt,
            Instant completedAt, Instant createdAt,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            java.util.List<String> inDoubtEffects) {
        /**
         * The run with the keys of its in-doubt effects, which a pause answer reports (#183): a pending pause does not
         * take effect while an effect is in doubt. Other answers omit the field.
         */
        RunView withInDoubtEffects(java.util.List<String> keys) {
            return new RunView(id, workspaceId, definitionId, versionId, releaseId, deploymentId, environmentId,
                    revisionId, cohortId, admission, routingGenerationId, routingTargetOrdinal, parentRunId, rootRunId,
                    continuationKind, traceId, manifestVersion, lifecycle, blockReason, terminalCode, eventSequence,
                    checkpointSequence, usage, startedAt, completedAt, createdAt, java.util.List.copyOf(keys));
        }

        static RunView of(RunRecord run) {
            var pins = run.pins();
            var summary = run.summary();
            return new RunView(pins.id(), pins.workspaceId(), pins.definitionId(), pins.versionId(), pins.releaseId(),
                    pins.deploymentId(), pins.environmentId(), pins.revisionId(), pins.cohortId(), pins.admission(),
                    pins.routingGenerationId(), pins.routingTargetOrdinal(), pins.parentRunId(), pins.rootRunId(),
                    pins.continuationKind(), pins.traceId(), pins.manifestVersion(), summary.lifecycle().name(),
                    summary.blockReason() == null ? null : summary.blockReason().name(), summary.terminalCode(),
                    summary.eventSequence(), summary.checkpointSequence(), UsageView.of(summary.usage()),
                    summary.startedAt(), summary.completedAt(), pins.createdAt(), null);
        }
    }

    public record UsageView(long inputTokens, long outputTokens, long totalTokens, long modelCalls, long toolCalls,
            long runtimeMillis, BigDecimal estimatedCostUsd) {
        static UsageView of(RunUsage usage) {
            return new UsageView(usage.inputTokens(), usage.outputTokens(), usage.totalTokens(), usage.modelCalls(),
                    usage.toolCalls(), usage.runtimeMillis(), usage.estimatedCostUsd());
        }
    }

    private static final Set<String> ACCEPTED_FIELDS = Set.of("input");
    /** Event pages are smaller than other collections because one event payload may be up to 128 KiB. */
    static final int DEFAULT_EVENT_LIMIT = 100;
    static final int MAX_EVENT_LIMIT = 200;
    private static final int MAX_LIFECYCLE_FILTERS = RunLifecycle.values().length;
    private final RunAdmissionService admissions;
    private final DurableRunEngine engine;
    private final WorkspaceScopeResolver workspaces;
    /**
     * Records the pause requests and withdrawals that no run event can record while a worker holds the run's lease
     * (#183) in the authentication and administration audit trail. It writes on the run engine's own data source, in
     * the same transaction as the change, so the change and its record commit or roll back together (#194).
     */
    private final ControlActionAudit audit;

    public RunAdmissionController(RunAdmissionService admissions, DurableRunEngine engine,
            WorkspaceScopeResolver workspaces) {
        this(admissions, engine, workspaces, ControlActionAudit.NONE);
    }

    public RunAdmissionController(RunAdmissionService admissions, DurableRunEngine engine,
            WorkspaceScopeResolver workspaces, ControlActionAudit audit) {
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @PostMapping(path = "/deployments/{deploymentId}/runs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RunView> admit(HttpServletRequest request, @PathVariable UUID deploymentId,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestHeader(name = "traceparent", required = false) String traceparent, @RequestBody JsonNode body) {
        var workspace = workspaces.resolve(request);
        if (!body.isObject() || !ACCEPTED_FIELDS.containsAll(body.propertyNames())) {
            throw new RunAdmissionRejectedException("CONTINUATION_NOT_EXTERNAL",
                    "only an input may be supplied; runs are admitted through current routing");
        }
        String traceId = traceId(traceparent);
        // The input is stored in the run's RUN_CREATED event; idempotency records only its HMAC fingerprint.
        AdmittedRun admitted = admissions.admitRoot(workspace, deploymentId, key, body.get("input"),
                new RunAdmissionService.Options(true, traceId, CurrentPrincipal.actor(), CurrentPrincipal.invoker(workspace)));
        var response = ResponseEntity.status(admitted.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .location(UriComponentsBuilder.fromPath("/api/v1/runs/{id}").build(admitted.run().id()));
        if (admitted.replayed()) response.header("Idempotent-Replayed", "true");
        RunRecord run = engine.run(workspace, admitted.run().id());
        return response.eTag(etag(run)).body(RunView.of(run));
    }

    /**
     * The trace id of a valid W3C traceparent, or null to start a new trace. Per W3C Trace Context an invalid header
     * (malformed, version ff, or an all-zero trace or parent id) is ignored rather than rejected; its value is never
     * logged.
     */
    static String traceId(String traceparent) {
        if (traceparent == null) return null;
        Matcher matcher = TRACEPARENT.matcher(traceparent);
        if (!matcher.matches() || "ff".equals(matcher.group(1))
                || ("00".equals(matcher.group(1)) && matcher.group(4) != null)
                || matcher.group(2).chars().allMatch(c -> c == '0')
                || matcher.group(3).chars().allMatch(c -> c == '0')) {
            LOG.debug("ignoring an invalid traceparent header; the run starts a new trace");
            return null;
        }
        return matcher.group(2);
    }

    /**
     * The workspace's runs, newest first, optionally filtered by deployment, release, and lifecycle (repeatable).
     * The cursor is the position of the last run of the previous page.
     */
    @GetMapping("/runs")
    public Page<RunView> runs(HttpServletRequest request,
            @RequestParam(name = "deploymentId", required = false) UUID deploymentId,
            @RequestParam(name = "releaseId", required = false) UUID releaseId,
            @RequestParam(name = "lifecycle", required = false) List<String> lifecycle,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        var workspace = workspaces.resolve(request);
        int size = PageCursor.limit(limit);
        List<String> position = PageCursor.decode(cursor, 2);
        RunQuery query = new RunQuery(deploymentId, releaseId, lifecycles(lifecycle),
                position == null ? null : PageCursor.instant(position.get(0)),
                position == null ? null : PageCursor.uuid(position.get(1)), size + 1);
        return Page.of(engine.runs(workspace, query), size, run -> PageCursor.encode(
                PageCursor.instant(run.pins().createdAt()), run.id().toString())).map(RunView::of);
    }

    /** The run's pins and summary; the ETag is its event sequence, which advances with every change of the view. */
    @GetMapping("/runs/{id}")
    public ResponseEntity<RunView> run(HttpServletRequest request, @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        RunRecord run = engine.run(workspaces.resolve(request), id);
        String etag = etag(run);
        if (notModified(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(RunView.of(run));
    }

    /**
     * One verified page of the run's events after the cursor, the decimal sequence of the last event already read
     * ({@code 0} or absent for the start). {@code nextCursor} is the sequence to continue from, or {@code null} once
     * the page reaches the run's current event sequence.
     */
    @GetMapping("/runs/{id}/events")
    public Page<RunEventView> events(HttpServletRequest request, @PathVariable UUID id,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        var workspace = workspaces.resolve(request);
        int size = PageCursor.limit(limit, DEFAULT_EVENT_LIMIT, MAX_EVENT_LIMIT);
        long after = cursor == null ? 0 : PageCursor.sequence(cursor);
        EventPage page = eventPage(engine, workspace, id, after, size);
        List<RunEventView> items = page.events().stream()
                .map(event -> RunEventView.of(event, engine.codec().schemas())).toList();
        return new Page<>(items, page.more() ? Long.toString(page.lastSequence(after)) : null);
    }

    /** Reads the bounded, durable context decision trail without exposing compiled or redacted content. */
    @GetMapping("/runs/{id}/context")
    public ContextDebugView context(HttpServletRequest request, @PathVariable UUID id) {
        WorkspaceId workspace = workspaces.resolve(request);
        long after = 0;
        List<RunEventView> context = new java.util.ArrayList<>();
        EventPage page;
        do {
            page = engine.eventPage(workspace, id, after, MAX_EVENT_LIMIT, null);
            for (var event : page.events()) {
                RunEventView view = RunEventView.of(event, engine.codec().schemas());
                if (view.category().equals("CONTEXT")) context.add(view);
            }
            after = page.lastSequence(after);
            if (context.size() > 5_000) {
                throw new ApiRequestException(HttpStatus.CONFLICT, "CONTEXT_DEBUG_TOO_LARGE",
                        "The run has more context decisions than the bounded debug response can return.");
            }
        } while (page.more());
        var pins = page.run().pins();
        return new ContextDebugView(id, pins.workspaceId(), pins.releaseId(), pins.deploymentId(), pins.revisionId(),
                pins.cohortId(), List.copyOf(context));
    }

    /**
     * Reads a page and turns a refused position into the resynchronization contract: a position beyond the run's
     * current sequence is 409 {@code EVENT_SEQUENCE_AHEAD}, and a position before retained history is 410
     * {@code EVENT_HISTORY_EXPIRED}; both carry the run's {@code currentSequence}, so a client re-reads the run and
     * continues from there instead of assuming a gap-free resume.
     */
    static EventPage eventPage(DurableRunEngine engine, WorkspaceId workspace, UUID runId, long after, int limit) {
        try {
            return engine.eventPage(workspace, runId, after, limit, null);
        } catch (RunEngineException refused) {
            HttpStatus status = switch (refused.code()) {
                case "EVENT_SEQUENCE_AHEAD" -> HttpStatus.CONFLICT;
                case "EVENT_HISTORY_EXPIRED" -> HttpStatus.GONE;
                default -> null;
            };
            if (status == null) throw refused;
            long current = engine.run(workspace, runId).summary().eventSequence();
            throw new ApiRequestException(status, refused.code(), status == HttpStatus.GONE
                    ? "Events after this position are no longer retained; re-read the run and continue from its current sequence."
                    : "The position is beyond the run's current event sequence; re-read the run and continue from its current sequence.",
                    Map.of("currentSequence", current), Map.of());
        }
    }

    static String etag(RunRecord run) {
        return "\"" + run.summary().eventSequence() + "\"";
    }

    /** RFC 9110 weak comparison of an {@code If-None-Match} list with a strong ETag. */
    static boolean notModified(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null) return false;
        for (String candidate : ifNoneMatch.split(",")) {
            String value = candidate.strip();
            if (value.equals("*")) return true;
            if (value.startsWith("W/")) value = value.substring(2);
            if (value.equals(etag)) return true;
        }
        return false;
    }

    private static Set<RunLifecycle> lifecycles(List<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        if (values.size() > MAX_LIFECYCLE_FILTERS) throw invalidFilter();
        Set<RunLifecycle> lifecycles = java.util.EnumSet.noneOf(RunLifecycle.class);
        for (String value : values) {
            try {
                lifecycles.add(RunLifecycle.valueOf(value));
            } catch (IllegalArgumentException unknown) {
                throw invalidFilter();
            }
        }
        return lifecycles;
    }

    private static ApiRequestException invalidFilter() {
        return new ApiRequestException(HttpStatus.BAD_REQUEST, "INVALID_FILTER",
                "lifecycle must name run lifecycles such as QUEUED, ACTIVE, or SUCCEEDED.");
    }

    /** The AgentStep projection, rebuilt from the run's verified events; it is a bounded projection and unpaged. */
    @GetMapping("/runs/{id}/steps")
    public Page<AgentStep> steps(HttpServletRequest request, @PathVariable UUID id) {
        return Page.of(engine.steps(workspaces.resolve(request), id));
    }

    @PostMapping("/runs/{id}/cancel")
    public RunView cancel(HttpServletRequest request, @PathVariable UUID id,
            @RequestBody(required = false) JsonNode body) {
        String reason = body != null && body.path("reason").isString() ? body.path("reason").stringValue() : null;
        return RunView.of(engine.cancel(workspaces.resolve(request), id, CurrentPrincipal.actor(), reason));
    }

    /**
     * An operator's pause (#183): a queued run pauses at once ({@code 200}); an active run pauses at its next safe
     * boundary ({@code 202} with the run as it still is). The request is recorded with the authenticated actor.
     */
    @PostMapping("/runs/{id}/pause")
    public ResponseEntity<RunView> pause(HttpServletRequest request, @PathVariable UUID id,
            @RequestBody(required = false) JsonNode body) {
        String reason = body != null && body.path("reason").isString() ? body.path("reason").stringValue() : null;
        var workspace = workspaces.resolve(request);
        String actor = CurrentPrincipal.actor();
        DurableRunEngine.PauseResult result = audit.record("RUN_PAUSE_REQUESTED",
                () -> engine.requestPause(workspace, id, actor, reason),
                done -> audited(done) ? new ControlActionAudit.Target("RUN", id, done.outcome().name(),
                        workspace) : null);
        RunView view = RunView.of(result.run());
        if (!result.inDoubtEffects().isEmpty()) view = view.withInDoubtEffects(result.inDoubtEffects());
        return ResponseEntity.status(result.pending() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(view);
    }

    /**
     * Whether a pause request is audited (#194, PR #199 review). A request that changed something is. A repeat of an
     * already paused run is not, and neither is a true repeat of a standing request by the same actor; another actor's
     * first repeat of a standing request is audited as {@code REPEATED}, so every operator who asked for the pause is
     * on record, and that actor's retries of it are not audited again (#201).
     */
    static boolean audited(DurableRunEngine.PauseResult result) {
        return switch (result.outcome()) {
            case PAUSED, REQUESTED -> true;
            case ALREADY_PAUSED -> false;
            case REPEATED -> result.firstRepeat();
        };
    }

    @PostMapping("/runs/{id}/resume")
    public RunView resume(HttpServletRequest request, @PathVariable UUID id) {
        var workspace = workspaces.resolve(request);
        String actor = CurrentPrincipal.actor();
        // An operator's resume answers an active run only by withdrawing its pending pause request (#183); that
        // withdrawal is audited atomically with it (#194). Any other resume is recorded by its own run event.
        RunRecord resumed = audit.record("RUN_PAUSE_WITHDRAWN", () -> engine.resumePaused(workspace, id, actor),
                done -> done.lifecycle() == RunLifecycle.ACTIVE
                        ? new ControlActionAudit.Target("RUN", id, "WITHDRAWN", workspace) : null);
        return RunView.of(resumed);
    }
}
