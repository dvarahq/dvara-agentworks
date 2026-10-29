package com.dvarahq.agentworks.console.web;

import com.dvarahq.agentworks.api.web.Page;
import com.dvarahq.agentworks.artifact.web.ArtifactApiResources.ArtifactResource;
import com.dvarahq.agentworks.artifact.web.ArtifactApiResources.PinRequest;
import com.dvarahq.agentworks.artifact.web.ArtifactApiResources.PinResource;
import com.dvarahq.agentworks.artifact.web.ArtifactApiResources.ValidationRequest;
import com.dvarahq.agentworks.artifact.web.ArtifactController;
import com.dvarahq.agentworks.controlplane.agent.web.AgentApiResources.AgentDefinitionResource;
import com.dvarahq.agentworks.controlplane.agent.web.AgentApiResources.AgentVersionResource;
import com.dvarahq.agentworks.controlplane.agent.web.AgentApiResources.CreateAgentDefinitionRequest;
import com.dvarahq.agentworks.controlplane.agent.web.AgentDefinitionController;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EnvironmentView;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionResult;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ReleaseView;
import com.dvarahq.agentworks.controlplane.lifecycle.web.DeploymentController;
import com.dvarahq.agentworks.controlplane.lifecycle.web.ReleaseEvidenceController;
import com.dvarahq.agentworks.controlplane.lifecycle.web.ReleaseEnvironmentController;
import com.dvarahq.agentworks.controlplane.lifecycle.web.ReleaseLifecycleController;
import com.dvarahq.agentworks.controlplane.capability.web.CapabilityCatalogController;
import com.dvarahq.agentworks.evaluation.web.EvaluationController;
import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.domain.artifact.ArtifactClassification;
import com.dvarahq.agentworks.domain.artifact.ArtifactPinKind;
import com.dvarahq.agentworks.domain.artifact.ArtifactValidation;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.DeploymentRow;
import com.dvarahq.agentworks.domain.run.AgentStep;
import com.dvarahq.agentworks.identity.web.AuthController;
import com.dvarahq.agentworks.identity.web.AdministrativeAuditController;
import com.dvarahq.agentworks.identity.web.ApiTokenController;
import com.dvarahq.agentworks.identity.web.MembershipController;
import com.dvarahq.agentworks.runplane.admission.web.RunAdmissionController;
import com.dvarahq.agentworks.runplane.admission.web.RunEventView;
import com.dvarahq.agentworks.runplane.interaction.web.HumanInteractionApiResources.InteractionResource;
import com.dvarahq.agentworks.runplane.interaction.web.HumanInteractionApiResources.ResponseRequest;
import com.dvarahq.agentworks.runplane.interaction.web.HumanInteractionController;
import com.dvarahq.agentworks.runplane.operation.web.DurableOperationController;
import com.dvarahq.agentworks.runplane.operation.web.OperationApiResources.OperationResource;
import com.dvarahq.agentworks.runplane.session.web.SessionApiResources.InboxResource;
import com.dvarahq.agentworks.runplane.session.web.SessionApiResources.SessionResource;
import com.dvarahq.agentworks.runplane.session.web.SessionController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The Console's only way into the application (issue #36, ADR-021). Every read and action invokes the same REST
 * controller bean that serves the corresponding {@code /api/v1} operation, with the Console's own request: the same
 * workspace scope, principal, validation, transactions, audit records, and outcomes. The Console never reaches a
 * service, repository, or database itself, and it keeps no state or authority of its own. A refusal is resolved by
 * the same exception handlers the API uses ({@link ConsoleProblems}), so the Console shows the API's problem code.
 */
public final class ConsoleApi {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AgentDefinitionController agents;
    private final ReleaseEnvironmentController releases;
    private final DeploymentController deployments;
    private final RunAdmissionController runs;
    private final HumanInteractionController interactions;
    private final SessionController sessions;
    private final DurableOperationController operations;
    private final ArtifactController artifacts;
    private final AuthController auth;
    private final ReleaseEvidenceController evidence;
    private final ReleaseLifecycleController releaseLifecycle;
    private final AdministrativeAuditController administrativeAudit;
    private final EvaluationController evaluations;
    private final CapabilityCatalogController capabilities;
    private final MembershipController memberships;
    private final ApiTokenController tokens;
    private final ConsoleProblems problems;

    public ConsoleApi(AgentDefinitionController agents, ReleaseEnvironmentController releases,
            DeploymentController deployments, RunAdmissionController runs, HumanInteractionController interactions,
            SessionController sessions, DurableOperationController operations, ArtifactController artifacts, AuthController auth,
            ReleaseEvidenceController evidence, ReleaseLifecycleController releaseLifecycle,
            AdministrativeAuditController administrativeAudit, EvaluationController evaluations,
            CapabilityCatalogController capabilities, MembershipController memberships, ApiTokenController tokens,
            ConsoleProblems problems) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.deployments = Objects.requireNonNull(deployments, "deployments");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.auth = Objects.requireNonNull(auth, "auth");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.releaseLifecycle = Objects.requireNonNull(releaseLifecycle, "releaseLifecycle");
        this.administrativeAudit = Objects.requireNonNull(administrativeAudit, "administrativeAudit");
        this.evaluations = Objects.requireNonNull(evaluations, "evaluations");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    // Agents and versions (GET/POST /api/v1/agents...).

    public Page<AgentDefinitionResource> agents(HttpServletRequest request, String cursor) {
        return call(request, AgentDefinitionController.class, () -> agents.list(request, cursor, null));
    }

    public AgentDefinitionResource createAgent(HttpServletRequest request, String name, String description) {
        return call(request, AgentDefinitionController.class, () -> agents.create(request,
                new CreateAgentDefinitionRequest(name, description)).getBody());
    }

    public AgentDefinitionResource agent(HttpServletRequest request, UUID id) {
        return call(request, AgentDefinitionController.class, () -> agents.get(request, id));
    }

    public Page<AgentVersionResource> versions(HttpServletRequest request, UUID definitionId, String cursor) {
        return call(request, AgentDefinitionController.class, () -> agents.versions(request, definitionId, cursor,
                null));
    }

    /** Publishes an AgentSpec document as YAML (JSON is valid YAML) exactly as {@code POST .../versions} does. */
    public AgentVersionResource publish(HttpServletRequest request, UUID definitionId, String document) {
        byte[] bytes = document == null ? new byte[0] : document.getBytes(StandardCharsets.UTF_8);
        // The API checks the declared length of its own body; the Console's form is not that body.
        HttpServletRequest body = new HttpServletRequestWrapper(request) {
            @Override
            public long getContentLengthLong() {
                return bytes.length;
            }

            @Override
            public int getContentLength() {
                return bytes.length;
            }
        };
        return call(request, AgentDefinitionController.class, () -> {
            try {
                return agents.publish(body, definitionId, MediaType.parseMediaType("application/yaml"),
                        new ByteArrayInputStream(bytes)).getBody();
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        });
    }

    // Releases and environments.

    public Page<ReleaseView> releases(HttpServletRequest request, String cursor) {
        return releases(request, cursor, null);
    }

    public Page<ReleaseView> releases(HttpServletRequest request, String cursor, Integer limit) {
        return call(request, ReleaseEnvironmentController.class, () -> releases.releases(request, cursor, limit));
    }

    public ReleaseView release(HttpServletRequest request, UUID id) {
        return call(request, ReleaseEnvironmentController.class, () -> releases.release(request, id).getBody());
    }

    public ReleaseView createRelease(HttpServletRequest request, UUID versionId, String name) {
        return call(request, ReleaseEnvironmentController.class, () -> releases.createRelease(request, versionId,
                new ReleaseEnvironmentController.CreateRelease(name)).getBody());
    }

    /** The release manifest, including the concrete model pinned for each model role. */
    public JsonNode manifest(HttpServletRequest request, UUID releaseId) {
        return call(request, ReleaseEnvironmentController.class,
                () -> JSON.readTree(releases.manifest(request, releaseId).getBody()));
    }

    public com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.Bundle releaseBundle(
            HttpServletRequest request, UUID releaseId) {
        return call(request, ReleaseEvidenceController.class, () -> evidence.exportBundle(request, releaseId).getBody());
    }

    public com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.Bundle signReleaseBundle(
            HttpServletRequest request, UUID releaseId) {
        return call(request, ReleaseEvidenceController.class, () -> evidence.sign(request, releaseId).getBody());
    }

    public com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ReleaseLifecycleView releaseLifecycle(
            HttpServletRequest request, UUID releaseId) {
        return call(request, ReleaseLifecycleController.class, () -> releaseLifecycle.get(request, releaseId).getBody());
    }

    public com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ReleaseLifecycleResult transitionRelease(
            HttpServletRequest request, UUID releaseId, String action, long version, String idempotencyKey,
            String reason) {
        return call(request, ReleaseLifecycleController.class, () -> releaseLifecycle.transition(request, releaseId,
                action, '"' + Long.toString(version) + '"', idempotencyKey,
                new ReleaseLifecycleController.Command(reason, null, null, null)).getBody());
    }

    public Page<EnvironmentView> environments(HttpServletRequest request) {
        return call(request, ReleaseEnvironmentController.class, () -> releases.environments(request, null, 200));
    }

    public EnvironmentView createEnvironment(HttpServletRequest request, String name, String description) {
        return call(request, ReleaseEnvironmentController.class, () -> releases.createEnvironment(request,
                new ReleaseEnvironmentController.CreateEnvironment(name, description, null)).getBody());
    }

    public EnvironmentView environment(HttpServletRequest request, UUID id) {
        return call(request, ReleaseEnvironmentController.class, () -> releases.environment(request, id).getBody());
    }

    public com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionPolicy updateEnvironmentPolicy(
            HttpServletRequest request, UUID id, long version,
            com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionPolicy policy) {
        return call(request, ReleaseEnvironmentController.class, () -> releases.updatePolicy(request, id,
                '"' + Long.toString(version) + '"', policy).getBody());
    }

    // Deployments and promotion.

    public Page<DeploymentRow> deployments(HttpServletRequest request, UUID environmentId, String cursor) {
        return call(request, DeploymentController.class, () -> deployments.deployments(request, environmentId, cursor,
                null));
    }

    public DeploymentController.DeploymentDetail deployment(HttpServletRequest request, UUID id) {
        return call(request, DeploymentController.class, () -> deployments.deployment(request, id).getBody());
    }

    public PromotionResult promote(HttpServletRequest request, UUID environmentId, String deployment, UUID releaseId,
            String reason) {
        return call(request, DeploymentController.class, () -> deployments.promote(request, environmentId, null, null,
                new DeploymentController.Promote(deployment, releaseId, null, reason)).getBody());
    }

    public com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.CohortResult transitionCohort(
            HttpServletRequest request, UUID cohortId, String action, long version, String idempotencyKey,
            String reason) {
        return call(request, DeploymentController.class, () -> deployments.transition(request, cohortId, action,
                idempotencyKey, '"' + Long.toString(version) + '"', new DeploymentController.Command(reason)).getBody());
    }

    public com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.RetirementResult retireCohort(
            HttpServletRequest request, UUID cohortId, long version, String idempotencyKey, String reason) {
        return call(request, DeploymentController.class, () -> deployments.retire(request, cohortId,
                idempotencyKey, '"' + Long.toString(version) + '"', new DeploymentController.Command(reason)).getBody());
    }

    public com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.QuiescenceReport observeQuiescence(
            HttpServletRequest request, UUID cohortId) {
        return call(request, DeploymentController.class,
                () -> deployments.quiescence(request, cohortId).getBody());
    }

    // Runs.

    public Page<RunAdmissionController.RunView> runs(HttpServletRequest request, UUID deploymentId, String lifecycle,
            String cursor) {
        return runs(request, deploymentId, null, lifecycle, cursor);
    }

    public Page<RunAdmissionController.RunView> runs(HttpServletRequest request, UUID deploymentId, UUID releaseId,
            String lifecycle, String cursor) {
        return call(request, RunAdmissionController.class, () -> runs.runs(request, deploymentId, releaseId,
                lifecycle == null ? null : List.of(lifecycle), cursor, null));
    }

    public RunAdmissionController.RunView run(HttpServletRequest request, UUID id) {
        return call(request, RunAdmissionController.class, () -> runs.run(request, id, null).getBody());
    }

    public Page<RunEventView> events(HttpServletRequest request, UUID runId, String cursor, int limit) {
        return call(request, RunAdmissionController.class, () -> runs.events(request, runId, cursor, limit));
    }

    public Page<AgentStep> steps(HttpServletRequest request, UUID runId) {
        return call(request, RunAdmissionController.class, () -> runs.steps(request, runId));
    }

    /** Launches a run with {@code input} (a JSON document) as {@code POST /deployments/{id}/runs} does. */
    public RunAdmissionController.RunView launch(HttpServletRequest request, UUID deploymentId, JsonNode input) {
        ObjectNode body = JSON.createObjectNode();
        body.set("input", input);
        return call(request, RunAdmissionController.class, () -> runs.admit(request, deploymentId, null, null, body)
                .getBody());
    }

    public RunAdmissionController.RunView cancel(HttpServletRequest request, UUID runId, String reason) {
        ObjectNode body = JSON.createObjectNode();
        if (reason != null && !reason.isBlank()) body.put("reason", reason);
        return call(request, RunAdmissionController.class, () -> runs.cancel(request, runId, body));
    }

    public RunAdmissionController.RunView pause(HttpServletRequest request, UUID runId, String reason) {
        ObjectNode body = JSON.createObjectNode();
        if (reason != null && !reason.isBlank()) body.put("reason", reason);
        return call(request, RunAdmissionController.class, () -> runs.pause(request, runId, body).getBody());
    }

    public RunAdmissionController.RunView resume(HttpServletRequest request, UUID runId) {
        return call(request, RunAdmissionController.class, () -> runs.resume(request, runId));
    }

    public RunAdmissionController.ContextDebugView context(HttpServletRequest request, UUID runId) {
        return call(request, RunAdmissionController.class, () -> runs.context(request, runId));
    }

    // Read-only feature, audit, and workspace views used by the complete Console (#58).

    public Page<AdministrativeAuditController.Resource> administrativeAudit(HttpServletRequest request,
            String eventType, String outcome, String targetType, UUID targetId, String cursor) {
        return call(request, AdministrativeAuditController.class, () -> administrativeAudit.list(request, eventType,
                outcome, targetType, targetId, cursor, null));
    }

    public EvaluationController.CollectionResource<EvaluationController.DatasetResource> evaluationDatasets(
            HttpServletRequest request) {
        return call(request, EvaluationController.class, () -> evaluations.datasets(request));
    }

    public EvaluationController.CollectionResource<com.dvarahq.agentworks.evaluation.EvaluationContracts.Evaluator>
            evaluators(HttpServletRequest request) {
        return call(request, EvaluationController.class, () -> evaluations.evaluators(request));
    }

    public EvaluationController.CollectionResource<com.dvarahq.agentworks.evaluation.EvaluationContracts.Baseline>
            evaluationBaselines(HttpServletRequest request) {
        return call(request, EvaluationController.class, () -> evaluations.baselines(request));
    }

    public Page<com.dvarahq.agentworks.controlplane.capability.web.CapabilityApiResources.ToolSummaryResource> tools(
            HttpServletRequest request, String cursor) {
        return call(request, CapabilityCatalogController.class,
                () -> capabilities.search(request, null, null, null, cursor, 50));
    }

    public Page<MembershipController.MemberView> members(HttpServletRequest request, AuthenticatedPrincipal principal) {
        return call(request, MembershipController.class, () -> memberships.list(principal));
    }

    public Page<ApiTokenController.TokenView> tokens(HttpServletRequest request, AuthenticatedPrincipal principal) {
        return call(request, ApiTokenController.class, () -> tokens.list(principal, false));
    }

    public static String prettyJson(Object value) {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }

    public static com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionPolicy promotionPolicy(
            String document) {
        try {
            return JSON.readValue(document,
                    com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionPolicy.class);
        } catch (RuntimeException unreadable) {
            throw ConsoleSupport.invalid("INVALID_PROMOTION_POLICY", "The promotion policy must be valid JSON.");
        }
    }

    // Human interactions.

    public Page<InteractionResource> interactions(HttpServletRequest request, String status, UUID runId,
            String cursor) {
        return call(request, HumanInteractionController.class,
                () -> interactions.list(request, status, runId, cursor, null));
    }

    public InteractionResource interaction(HttpServletRequest request, UUID id) {
        return call(request, HumanInteractionController.class, () -> interactions.get(request, id));
    }

    public InteractionResource respond(HttpServletRequest request, UUID id, ResponseRequest response) {
        return call(request, HumanInteractionController.class, () -> interactions.respond(request, id, response));
    }

    // Logical sessions, inbox, and durable operations.

    public Page<SessionResource> sessions(HttpServletRequest request, String cursor) {
        return call(request, SessionController.class, () -> sessions.list(request, cursor, null));
    }

    public SessionResource session(HttpServletRequest request, UUID id) {
        return call(request, SessionController.class, () -> sessions.get(request, id));
    }

    public Page<InboxResource> inbox(HttpServletRequest request, UUID sessionId, String status, String cursor) {
        return call(request, SessionController.class, () -> sessions.messages(request, sessionId, status, cursor, null));
    }

    public Page<OperationResource> operations(HttpServletRequest request, UUID sessionId, String status, String cursor) {
        return call(request, DurableOperationController.class,
                () -> operations.list(request, sessionId, status, cursor, null));
    }

    public OperationResource operation(HttpServletRequest request, UUID id) {
        return call(request, DurableOperationController.class, () -> operations.get(request, id));
    }

    public OperationResource cancelOperation(HttpServletRequest request, UUID id) {
        return call(request, DurableOperationController.class, () -> operations.cancel(request, id));
    }

    // Artifacts.

    public Page<ArtifactResource> artifacts(HttpServletRequest request, String cursor) {
        return call(request, ArtifactController.class, () -> artifacts.list(request, null, null, cursor, null));
    }

    public ArtifactResource artifact(HttpServletRequest request, UUID id) {
        return call(request, ArtifactController.class, () -> artifacts.metadata(request, id));
    }

    public Page<PinResource> artifactPins(HttpServletRequest request, UUID id) {
        return call(request, ArtifactController.class, () -> artifacts.pins(request, id));
    }

    public ArtifactResource uploadArtifact(HttpServletRequest request, String mediaType, byte[] content,
            ArtifactClassification classification, boolean redacted) {
        HttpServletRequest body = new HttpServletRequestWrapper(request) {
            @Override public long getContentLengthLong() { return content.length; }
            @Override public int getContentLength() { return content.length; }
            @Override public String getContentType() { return mediaType; }
            @Override public jakarta.servlet.ServletInputStream getInputStream() {
                ByteArrayInputStream input = new ByteArrayInputStream(content);
                return new jakarta.servlet.ServletInputStream() {
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(jakarta.servlet.ReadListener listener) {}
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] bytes, int offset, int length) {
                        return input.read(bytes, offset, length);
                    }
                };
            }
        };
        return call(request, ArtifactController.class, () -> {
            try {
                return artifacts.upload(body, null, classification, redacted, null).getBody();
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        });
    }

    public ArtifactResource expireArtifact(HttpServletRequest request, UUID id) {
        return call(request, ArtifactController.class, () -> artifacts.expire(request, id));
    }

    public ArtifactResource validateArtifact(HttpServletRequest request, UUID id, ArtifactValidation validation,
            String reason) {
        return call(request, ArtifactController.class,
                () -> artifacts.validate(request, id, new ValidationRequest(validation, reason)));
    }

    public PinResource pinArtifact(HttpServletRequest request, UUID id, ArtifactPinKind kind, UUID subjectId) {
        return call(request, ArtifactController.class,
                () -> artifacts.pin(request, id, new PinRequest(kind, subjectId, null)).getBody());
    }

    public void unpinArtifact(HttpServletRequest request, UUID id, UUID pinId) {
        call(request, ArtifactController.class, () -> artifacts.unpin(request, id, pinId));
    }

    // Sessions and workspaces (/api/v1/auth...).

    public AuthController.SessionView session(HttpServletRequest request, AuthenticatedPrincipal principal) {
        return call(request, AuthController.class, () -> auth.session(principal, request));
    }

    public List<AuthController.WorkspaceView> workspaces(HttpServletRequest request, AuthenticatedPrincipal principal) {
        return call(request, AuthController.class, () -> auth.workspaces(principal).items());
    }

    /**
     * Signs in as {@code POST /auth/login} does, which sets the session cookie on {@code response}. A throttled
     * attempt is answered by the API as a problem response rather than an exception; it is returned as one here.
     */
    public AuthController.SessionView login(HttpServletRequest request, HttpServletResponse response, String username,
            String password) {
        return call(request, AuthController.class, () -> {
            ResponseEntity<?> answer = auth.login(request, response, new AuthController.LoginRequest(username, password,
                    null));
            if (answer.getBody() instanceof AuthController.SessionView session) return session;
            throw ConsoleProblems.fromResponse(answer);
        });
    }

    public void logout(HttpServletRequest request, AuthenticatedPrincipal principal, HttpServletResponse response) {
        call(request, AuthController.class, () -> auth.logout(principal, response));
    }

    public AuthController.SessionView changePassword(HttpServletRequest request, AuthenticatedPrincipal principal,
            HttpServletResponse response, String current, String replacement) {
        return call(request, AuthController.class, () -> auth.changePassword(principal, response,
                new AuthController.PasswordChange(current, replacement)));
    }

    public AuthController.SessionView selectWorkspace(HttpServletRequest request, AuthenticatedPrincipal principal,
            HttpServletResponse response, UUID workspaceId) {
        return call(request, AuthController.class, () -> auth.selectWorkspace(principal, request, response,
                new AuthController.WorkspaceSelection(workspaceId)));
    }

    /** Runs one API call and turns any refusal into the API's own problem for the Console to show. */
    private <T> T call(HttpServletRequest request, Class<?> controller, Supplier<T> operation) {
        try {
            return operation.get();
        } catch (ConsoleProblemException resolved) {
            throw resolved;
        } catch (RuntimeException refused) {
            throw new ConsoleProblemException(problems.resolve(request, controller, refused));
        }
    }
}
