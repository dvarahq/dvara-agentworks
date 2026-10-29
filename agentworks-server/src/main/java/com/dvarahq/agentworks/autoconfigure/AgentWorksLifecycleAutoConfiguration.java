package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.capability.CapabilityPackRegistry;
import com.dvarahq.agentworks.capability.ToolRegistry;
import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.controlplane.deployment.DeploymentLifecycleService;
import com.dvarahq.agentworks.controlplane.deployment.EnvironmentService;
import com.dvarahq.agentworks.controlplane.deployment.PromotionEvidenceProvider;
import com.dvarahq.agentworks.controlplane.deployment.ReleaseBundlePromotionEvidenceProvider;
import com.dvarahq.agentworks.controlplane.deployment.ReleaseLifecycleService;
import com.dvarahq.agentworks.controlplane.deployment.EvaluationPromotionEvidenceSource;
import com.dvarahq.agentworks.controlplane.deployment.DependencyContinuityProvider;
import com.dvarahq.agentworks.controlplane.deployment.HumanApprovalEvidenceSource;
import com.dvarahq.agentworks.controlplane.deployment.DependencyAvailabilitySource;
import com.dvarahq.agentworks.controlplane.lifecycle.ReleaseService;
import com.dvarahq.agentworks.controlplane.evidence.EvidenceAnchor;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseAdmissionPolicy;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidenceAuthority;
import com.dvarahq.agentworks.controlplane.lifecycle.web.DeploymentController;
import com.dvarahq.agentworks.controlplane.lifecycle.web.LifecycleApiExceptionHandler;
import com.dvarahq.agentworks.controlplane.lifecycle.web.OutboxRecoveryController;
import com.dvarahq.agentworks.controlplane.lifecycle.web.ReleaseEnvironmentController;
import com.dvarahq.agentworks.controlplane.lifecycle.web.ReleaseLifecycleController;
import com.dvarahq.agentworks.controlplane.lifecycle.web.ReleaseEvidenceController;
import com.dvarahq.agentworks.gateway.GatewayRegistry;
import com.dvarahq.agentworks.gateway.ModelRoleResolver;
import com.dvarahq.agentworks.gateway.RestrictedGatewayTypes;
import com.dvarahq.agentworks.gateway.fake.DeterministicFakeGatewayAdapter;
import com.dvarahq.agentworks.controlplane.deployment.ControlPlaneOutboxDispatcher;
import com.dvarahq.agentworks.domain.run.RunLimits;
import com.dvarahq.agentworks.credential.CredentialAuditSink;
import com.dvarahq.agentworks.credential.CredentialProvider;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionService;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionPolicy;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionObserver;
import com.dvarahq.agentworks.runplane.admission.web.RunAdmissionController;
import com.dvarahq.agentworks.runplane.admission.web.RunEventView;
import com.dvarahq.agentworks.runplane.admission.web.RunStreamController;
import com.dvarahq.agentworks.runplane.admission.web.RunStreamReleaseFilter;
import com.dvarahq.agentworks.runplane.admission.web.ReplayController;
import com.dvarahq.agentworks.runplane.admission.web.ReplayApiExceptionHandler;
import com.dvarahq.agentworks.replay.ReplayModelExecutor;
import com.dvarahq.agentworks.replay.ReplayService;
import com.dvarahq.agentworks.replay.RecordedOutcomeReader;
import com.dvarahq.agentworks.runplane.stream.RunEventStreams;
import com.dvarahq.agentworks.identity.PrincipalRevalidation;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.DurableHumanInteractionCoordinator;
import com.dvarahq.agentworks.runplane.execution.DelegatedAuthorizationExchange;
import com.dvarahq.agentworks.runplane.execution.EventSchemas;
import com.dvarahq.agentworks.runplane.execution.ReleaseManifestReader;
import com.dvarahq.agentworks.runplane.execution.RunEventCodec;
import com.dvarahq.agentworks.runplane.execution.RunLimitsPolicy;
import com.dvarahq.agentworks.runplane.execution.RunRecoveryObserver;
import com.dvarahq.agentworks.runplane.execution.ManifestRunLimitsPolicy;
import com.dvarahq.agentworks.runplane.execution.DurableOperationCoordinator;
import com.dvarahq.agentworks.runplane.execution.DurableChildRunCoordinator;
import com.dvarahq.agentworks.runplane.execution.ScheduledWakeupCoordinator;
import com.dvarahq.agentworks.runplane.execution.AgentAsTool;
import com.dvarahq.agentworks.runplane.execution.OperationCallbackTokens;
import com.dvarahq.agentworks.runplane.execution.SessionDeliveryCoordinator;
import com.dvarahq.agentworks.runplane.exposure.AgentExposureCoordinator;
import com.dvarahq.agentworks.runplane.exposure.ExposureException;
import com.dvarahq.agentworks.runplane.exposure.ExposureRevisionSource;
import com.dvarahq.agentworks.runplane.exposure.FixedWindowExposureRateLimiter;
import com.dvarahq.agentworks.exposure.ExposureRateLimiter;
import com.dvarahq.agentworks.exposure.InteractionChannel;
import com.dvarahq.agentworks.runplane.execution.SessionInboxCoordinator;
import com.dvarahq.agentworks.operation.DurableOperationProvider;
import com.dvarahq.agentworks.runplane.reconciliation.DeploymentReconciler;
import com.dvarahq.agentworks.runplane.interaction.web.HumanInteractionApiExceptionHandler;
import com.dvarahq.agentworks.runplane.interaction.web.HumanInteractionController;
import com.dvarahq.agentworks.runplane.operation.web.DurableOperationApiExceptionHandler;
import com.dvarahq.agentworks.runplane.operation.web.DurableOperationController;
import com.dvarahq.agentworks.runplane.session.web.SessionApiExceptionHandler;
import com.dvarahq.agentworks.runplane.session.web.SessionController;
import com.dvarahq.agentworks.spec.AgentSpecCodec;
import com.dvarahq.agentworks.storage.JdbcAgentDefinitionRepository;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import com.dvarahq.agentworks.storage.lifecycle.IdempotencyDigests;
import com.dvarahq.agentworks.storage.lifecycle.JdbcLifecycleRepository;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import com.dvarahq.agentworks.controlplane.lifecycle.web.PromotionApprovalController;
import com.dvarahq.agentworks.storage.interaction.JdbcHumanInteractionAuthority;
import com.dvarahq.agentworks.storage.operation.JdbcDurableOperationAuthority;
import com.dvarahq.agentworks.storage.child.JdbcChildRunAuthority;
import com.dvarahq.agentworks.storage.session.JdbcSessionInboxAuthority;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.List;

import java.time.Duration;
import java.time.Clock;
import com.dvarahq.agentworks.evaluation.EvaluationService;

/**
 * Wires the release, environment, deployment, cohort, run-admission, and durable-run lifecycle and its provisional
 * REST API. {@code agentworks.lifecycle.idempotency-replay-window} (ISO-8601, default PT24H) publishes the
 * detailed-result replay window for lifecycle commands. {@code agentworks.runs.limits.*} (max-total-tokens,
 * max-model-calls, max-tool-calls, max-runtime as ISO-8601, max-cost-usd) sets the limits new runs are pinned to;
 * unset limits are unbounded. Run Plane startup reconciliation re-observes every deployment once at startup
 * ({@code agentworks.runs.startup-reconciliation.enabled}, default true). The outbox recovery loop is controlled by
 * {@code agentworks.recovery.outbox.*}; workers remain application-composed.
 */
@AutoConfiguration(after = {AgentWorksJdbcAutoConfiguration.class, AgentWorksAgentDefinitionAutoConfiguration.class})
@ConditionalOnProperty(prefix = "agentworks.lifecycle", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean({TenantTransactionTemplate.class, JdbcLifecycleRepository.class, JdbcAgentDefinitionRepository.class,
        AgentSpecCodec.class, TransactionTemplate.class})
public final class AgentWorksLifecycleAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    AgentWorksPlanes agentWorksPlanes(DataSource dataSource, TransactionTemplate transactions, Environment environment) {
        return AgentWorksPlanes.from(environment, dataSource, transactions);
    }

    /**
     * Versioned HMAC keys ({@code agentworks.idempotency.hmac-keys.<version>} as Base64, at least 32 bytes) and
     * {@code agentworks.idempotency.active-key-version}. Without keys, requests carrying an idempotency key are
     * refused rather than digested with an unkeyed hash.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agentworks.idempotency", name = "active-key-version")
    IdempotencyDigests agentWorksIdempotencyDigests(Environment environment) {
        Map<String, String> encoded = Binder.get(environment)
                .bind("agentworks.idempotency.hmac-keys", Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        Map<Integer, byte[]> keys = new TreeMap<>();
        encoded.forEach((version, material) -> keys.put(Integer.valueOf(version), Base64.getDecoder().decode(material)));
        return new IdempotencyDigests(keys, environment.getRequiredProperty("agentworks.idempotency.active-key-version",
                Integer.class));
    }

    /**
     * Without a gateway registry (for example {@code agentworks.gateway.enabled=false}) model roles fail closed. More
     * than one non-primary {@link ModelRoleResolver} fails startup rather than silently disabling resolution.
     */
    @Bean
    @ConditionalOnMissingBean
    ReleaseService agentWorksReleaseService(AgentWorksPlanes planes, AgentSpecCodec codec, LifecycleRetry retry,
            ObjectProvider<ModelRoleResolver> models, CapabilityPackRegistry packs, ToolRegistry tools,
            ObjectProvider<com.dvarahq.agentworks.capability.SkillRegistry> skills,
            Environment environment) {
        var control = planes.control();
        return new ReleaseService(control.tenants(), control.lifecycle(), control.agents(), codec, control.clock(), retry,
                releaseResolver(environment, models.getIfAvailable(GatewayRegistry::empty)), packs, tools,
                skills.getIfAvailable(com.dvarahq.agentworks.capability.SkillRegistry::empty));
    }

    /**
     * The signing private key is Control Plane configuration only. The Run Plane bean graph never receives this
     * authority. One trust set plus explicit revoked key ids is the OSS 1.0 trust model.
     */
    @Bean
    @ConditionalOnMissingBean
    ReleaseEvidenceAuthority agentWorksReleaseEvidenceAuthority(AgentWorksPlanes planes, Environment environment,
            ObjectProvider<EvidenceAnchor> anchors) {
        Map<String, String> encoded = Binder.get(environment).bind("agentworks.release-evidence.trusted-public-keys",
                Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        Map<String, byte[]> keys = new TreeMap<>();
        encoded.forEach((id, value) -> keys.put(id, Base64.getDecoder().decode(value)));
        String active = environment.getProperty("agentworks.release-evidence.active-key-id");
        List<String> revoked = Binder.get(environment).bind("agentworks.release-evidence.revoked-key-ids",
                Bindable.listOf(String.class)).orElse(List.of());
        ReleaseEvidence.Ed25519Signer signer = null;
        if (active != null) {
            byte[] publicKey = keys.get(active);
            if (publicKey == null) throw new IllegalStateException("active release signing key must be in trusted-public-keys");
            String privateValue = environment.getRequiredProperty("agentworks.release-evidence.private-key");
            signer = new ReleaseEvidence.Ed25519Signer(active, Base64.getDecoder().decode(privateValue), publicKey);
        }
        var trust = new ReleaseEvidence.TrustPolicy(keys, revoked);
        var control = planes.control();
        return new ReleaseEvidenceAuthority(control.tenants(), control.lifecycle(), control.clock(), signer, trust,
                anchors.getIfUnique(EvidenceAnchor::none));
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcPromotionApprovalAuthority agentWorksPromotionApprovalAuthority(AgentWorksPlanes planes,
            TenantTransactionTemplate tenants) {
        var control = planes.control();
        return new JdbcPromotionApprovalAuthority(new org.springframework.jdbc.core.JdbcTemplate(control.dataSource()),
                tenants, control.clock());
    }

    @Bean
    @ConditionalOnMissingBean
    PromotionEvidenceProvider agentWorksPromotionEvidenceProvider(ReleaseEvidenceAuthority authority,
            ObjectProvider<EvaluationService> evaluations,
            JdbcPromotionApprovalAuthority approvals) {
        EvaluationPromotionEvidenceSource source = evaluations.getIfAvailable() == null
                ? EvaluationPromotionEvidenceSource.NONE
                : new EvaluationPromotionEvidenceAdapter(evaluations.getIfAvailable());
        return new ReleaseBundlePromotionEvidenceProvider(authority, source, approvals);
    }

    @Bean
    @ConditionalOnMissingBean
    DependencyContinuityProvider agentWorksDependencyContinuityProvider(ObjectProvider<ModelRoleResolver> models,
            CapabilityPackRegistry packs, ToolRegistry tools,
            ObjectProvider<com.dvarahq.agentworks.capability.SkillRegistry> skills,
            ObjectProvider<DependencyAvailabilitySource> availability,
            ObjectProvider<com.dvarahq.agentworks.agent.AgentExecutor> executors,
            ObjectProvider<com.dvarahq.agentworks.sandbox.SandboxProvider> sandboxes) {
        List<DependencyAvailabilitySource> sources = new java.util.ArrayList<>(availability.orderedStream().toList());
        if (sources.stream().noneMatch(source -> source.type()
                == com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.DependencyType.POLICY)) sources.add(new DependencyAvailabilitySource() {
            @Override public com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.DependencyType type() {
                return com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.DependencyType.POLICY;
            }
            @Override public java.util.Optional<Object> capture(com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace,
                    Object approved) { return java.util.Optional.of(approved); }
        });
        if (sources.stream().noneMatch(source -> source.type()
                == com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.DependencyType.RUNTIME)) sources.add(new DependencyAvailabilitySource() {
            @Override public com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.DependencyType type() {
                return com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.DependencyType.RUNTIME;
            }
            @Override public java.util.Optional<Object> capture(com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace,
                    Object approved) {
                return runtimeAvailability(executors.orderedStream().toList(), sandboxes.orderedStream().toList(),
                        approved);
            }
        });
        return new RegistryDependencyContinuityProvider(models.getIfAvailable(GatewayRegistry::empty), packs, tools,
                skills.getIfAvailable(com.dvarahq.agentworks.capability.SkillRegistry::empty), sources);
    }

    static java.util.Optional<Object> runtimeAvailability(
            java.util.List<com.dvarahq.agentworks.agent.AgentExecutor> executors,
            java.util.List<com.dvarahq.agentworks.sandbox.SandboxProvider> sandboxes, Object approved) {
        if (!(approved instanceof Map<?, ?> runtime) || !(runtime.get("executor") instanceof Map<?, ?> executor)) {
            return java.util.Optional.empty();
        }
        Object type = executor.get("type");
        if (!executors.isEmpty() && executors.stream()
                .noneMatch(candidate -> java.util.Objects.equals(candidate.type(), type))) {
            return java.util.Optional.empty();
        }
        if (runtime.get("sandbox") instanceof Map<?, ?> sandbox && !sandbox.isEmpty()) {
            String expected = java.util.Objects.toString(sandbox.get("provider"), "");
            if (!sandboxes.isEmpty() && sandboxes.stream()
                    .noneMatch(candidate -> candidate.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT)
                            .contains(expected.toLowerCase(java.util.Locale.ROOT)))) {
                return java.util.Optional.empty();
            }
        }
        if (executors.isEmpty()) return java.util.Optional.empty();
        if (runtime.get("sandbox") instanceof Map<?, ?> sandbox && !sandbox.isEmpty() && sandboxes.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(approved);
    }

    /**
     * Defense in depth behind startup validation: outside the local-development gate no release can pin a model role to
     * the deterministic fake, even through an application-supplied resolver ({@code GATEWAY_NOT_PERMITTED}).
     */
    static ModelRoleResolver releaseResolver(Environment environment, ModelRoleResolver resolver) {
        if (AgentWorksLocalDevelopment.enabled(environment)) {
            return resolver;
        }
        return RestrictedGatewayTypes.excluding(resolver, Set.of(DeterministicFakeGatewayAdapter.TYPE),
                "is permitted only for local development");
    }

    @Bean
    @ConditionalOnMissingBean
    EnvironmentService agentWorksEnvironmentService(AgentWorksPlanes planes, LifecycleRetry retry) {
        var control = planes.control();
        return new EnvironmentService(control.tenants(), control.lifecycle(), control.clock(), retry);
    }

    @Bean
    @ConditionalOnMissingBean
    ReleaseLifecycleService agentWorksReleaseLifecycleService(AgentWorksPlanes planes, LifecycleRetry retry,
            ObjectProvider<IdempotencyDigests> digests, Environment environment) {
        var control = planes.control();
        Duration window = Duration.parse(environment.getProperty("agentworks.lifecycle.idempotency-replay-window", "PT24H"));
        return new ReleaseLifecycleService(control.tenants(), control.lifecycle(), control.idempotency(),
                control.clock(), retry, window, digests.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    DeploymentLifecycleService agentWorksDeploymentLifecycleService(AgentWorksPlanes planes, LifecycleRetry retry,
            ObjectProvider<IdempotencyDigests> digests, ObjectProvider<ReleaseAdmissionPolicy> releasePolicies,
            ReleaseLifecycleService releaseLifecycle,
            ObjectProvider<PromotionEvidenceProvider> evidence,
            ObjectProvider<DependencyContinuityProvider> continuity,
            Environment environment) {
        Duration window = Duration.parse(environment.getProperty("agentworks.lifecycle.idempotency-replay-window", "PT24H"));
        var control = planes.control();
        boolean required = environment.getProperty("agentworks.release-evidence.required", Boolean.class, false);
        ReleaseAdmissionPolicy evidencePolicy = releasePolicies.orderedStream()
                .filter(candidate -> candidate != releaseLifecycle).findFirst().orElse(null);
        if (required && evidencePolicy == null) {
            throw new IllegalStateException("agentworks.release-evidence.required needs a configured signing authority");
        }
        ReleaseAdmissionPolicy policy = required
                ? ReleaseAdmissionPolicy.allOf(releaseLifecycle, evidencePolicy) : releaseLifecycle;
        return new DeploymentLifecycleService(control.tenants(), control.lifecycle(), control.idempotency(),
                control.clock(), retry, window, digests.getIfAvailable(),
                policy,
                evidence.getIfAvailable(() -> PromotionEvidenceProvider.NONE),
                continuity.getIfAvailable(() -> DependencyContinuityProvider.MANIFEST));
    }

    @Bean
    @ConditionalOnMissingBean
    RunRecoveryObserver agentWorksRunRecoveryObserver(
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registries) {
        var metrics = registries.getIfAvailable();
        if (metrics == null) return RunRecoveryObserver.NONE;
        return delay -> {
            metrics.counter("agentworks.run.recoveries").increment();
            metrics.timer("agentworks.run.recovery.delay").record(delay);
        };
    }

    @Bean
    @ConditionalOnMissingBean
    DurableRunEngine agentWorksDurableRunEngine(AgentWorksPlanes planes, LifecycleRetry retry,
            ObjectProvider<EventSchemas> schemas, Environment environment,
            ObjectProvider<com.dvarahq.agentworks.runplane.execution.ExecutorCapabilities.Declaring> executors,
            RunRecoveryObserver recoveryObserver) {
        RunEventCodec codec = new RunEventCodec(schemas.getIfAvailable(() -> EventSchemas.CURRENT));
        // What each executor type promises (#183). An executor type no bean declares on this node promises nothing, so
        // an operator's pause of its active runs is refused (PAUSE_UNSAFE) rather than risking repeated work.
        java.util.List<com.dvarahq.agentworks.runplane.execution.ExecutorCapabilities.Declaring> declared =
                executors.orderedStream().toList();
        return new DurableRunEngine(planes.run().runStore(), codec, new ReleaseManifestReader(), retry,
                compatibility(codec, environment, DurableRunEngine.buildStamped()),
                com.dvarahq.agentworks.runplane.execution.ExecutorCapabilities.Registry.of(declared), recoveryObserver);
    }

    /** Human interactions are run-plane authority so their block/resume transitions share one JDBC transaction. */
    @Bean
    @ConditionalOnMissingBean
    JdbcHumanInteractionAuthority agentWorksHumanInteractionAuthority(AgentWorksPlanes planes) {
        var run = planes.run();
        return new JdbcHumanInteractionAuthority(run.jdbc(), run.transactions());
    }

    @Bean
    @ConditionalOnMissingBean
    DurableHumanInteractionCoordinator agentWorksHumanInteractionCoordinator(AgentWorksPlanes planes,
            DurableRunEngine engine, JdbcHumanInteractionAuthority interactions, LifecycleRetry retry,
            ObjectProvider<CredentialProvider> credentials, ObjectProvider<CredentialAuditSink> audits,
            ObjectProvider<Clock> clocks) {
        CredentialProvider provider = credentials.getIfUnique();
        CredentialAuditSink audit = audits.getIfUnique();
        DelegatedAuthorizationExchange exchange = provider == null || audit == null ? null
                : new DelegatedAuthorizationExchange(provider, audit, clocks.getIfUnique(Clock::systemUTC));
        return new DurableHumanInteractionCoordinator(planes.run().transactions(), engine, interactions, retry,
                exchange);
    }

    /** Session, inbox, and operation authorities share the Run Plane's one local Spring JDBC transaction. */
    @Bean
    @ConditionalOnMissingBean
    JdbcSessionInboxAuthority agentWorksSessionInboxAuthority(AgentWorksPlanes planes) {
        var run = planes.run();
        return new JdbcSessionInboxAuthority(run.jdbc(), run.transactions());
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcDurableOperationAuthority agentWorksDurableOperationAuthority(AgentWorksPlanes planes) {
        var run = planes.run();
        return new JdbcDurableOperationAuthority(run.jdbc(), run.transactions());
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcChildRunAuthority agentWorksChildRunAuthority(AgentWorksPlanes planes) {
        var run = planes.run();
        return new JdbcChildRunAuthority(run.jdbc(), run.transactions());
    }

    @Bean
    @ConditionalOnMissingBean
    SessionInboxCoordinator agentWorksSessionInboxCoordinator(AgentWorksPlanes planes, LifecycleRetry retry,
            DurableRunEngine engine, JdbcSessionInboxAuthority inbox) {
        return new SessionInboxCoordinator(planes.run().transactions(), retry, engine, inbox);
    }

    /** Names the build of a node whose runtime module carries no build stamp (#185). */
    static final String BUILD_ID = "agentworks.runtime.build-id";

    /**
     * This node's runtime compatibility identity (#185). A build stamped by Maven from a git checkout uses its commit,
     * and {@value #BUILD_ID} is ignored for it (#189), so a name left in place never merges stamped builds. An
     * unstamped build uses {@value #BUILD_ID}, with its project version, when set. An unstamped build without it
     * shares one identity with every other unstamped build of its version, so a quarantine one of them records would
     * stop them all: it is refused at startup except under the local-development gate, where it is logged at WARN.
     */
    static String compatibility(RunEventCodec codec, Environment environment, boolean stamped) {
        String buildId = environment.getProperty(BUILD_ID);
        if (stamped) {
            if (buildId != null && !buildId.isBlank()) {
                LOG.warn("{} is ignored: this runtime carries a build stamp, which names its build.", BUILD_ID);
            }
            return DurableRunEngine.compatibilityOf(codec);
        }
        if (buildId != null && !buildId.isBlank()) {
            String compatibility = DurableRunEngine.compatibilityOf(codec, buildId.strip());
            LOG.info("The runtime compatibility identity is {}, named by {}.", compatibility, BUILD_ID);
            return compatibility;
        }
        String compatibility = DurableRunEngine.compatibilityOf(codec);
        if (!AgentWorksLocalDevelopment.enabled(environment)) {
            throw new IllegalStateException("The runtime carries no build stamp (built without git, or git could not "
                    + "read the checkout), so every such build of this version would share the compatibility identity "
                    + compatibility + " and its claim quarantines. Build from a git checkout, or set " + BUILD_ID
                    + " to a value that differs between builds.");
        }
        LOG.warn("The runtime carries no build stamp: its compatibility identity {} is shared by every unstamped build "
                + "of this version (allowed only under {}).", compatibility, AgentWorksLocalDevelopment.requirement());
        return compatibility;
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(
            AgentWorksLifecycleAutoConfiguration.class);

    /**
     * Lists and releases claim quarantines (#181) on the Control Plane connection, which alone may release one (V11_1);
     * the administrative API over it is #79.
     */
    @Bean
    @ConditionalOnMissingBean
    com.dvarahq.agentworks.storage.run.JdbcClaimQuarantineRepository agentWorksClaimQuarantines(AgentWorksPlanes planes) {
        return planes.control().claimQuarantines();
    }

    @Bean
    @ConditionalOnMissingBean
    RunLimitsPolicy agentWorksRunLimitsPolicy(Environment environment) {
        String runtime = environment.getProperty("agentworks.runs.limits.max-runtime");
        String cost = environment.getProperty("agentworks.runs.limits.max-cost-usd");
        return new ManifestRunLimitsPolicy(new RunLimits(
                environment.getProperty("agentworks.runs.limits.max-total-tokens", Long.class),
                environment.getProperty("agentworks.runs.limits.max-model-calls", Long.class),
                environment.getProperty("agentworks.runs.limits.max-tool-calls", Long.class),
                runtime == null ? null : Duration.parse(runtime).toMillis(),
                cost == null ? null : new java.math.BigDecimal(cost)));
    }

    @Bean
    @ConditionalOnMissingBean
    RunAdmissionPolicy agentWorksRunAdmissionPolicy(Environment environment) {
        String prefix = "agentworks.runs.admission.";
        String tokens = environment.getProperty(prefix + "max-workspace-reserved-tokens");
        String cost = environment.getProperty(prefix + "max-workspace-reserved-cost-usd");
        return new RunAdmissionPolicy(environment.getProperty(prefix + "policy-version", "default-v1"),
                environment.getProperty(prefix + "max-workspace-outstanding", Long.class, 1_000L),
                environment.getProperty(prefix + "max-deployment-outstanding", Long.class, 100L),
                environment.getProperty(prefix + "max-workspace-queued", Long.class, 900L),
                environment.getProperty(prefix + "max-deployment-queued", Long.class, 90L),
                environment.getProperty(prefix + "max-roots-per-window", Long.class, 600L),
                Duration.parse(environment.getProperty(prefix + "rate-window", "PT1M")),
                environment.getProperty(prefix + "max-sandbox-outstanding", Long.class, 100L),
                java.util.Map.of("SHARED", environment.getProperty(prefix + "max-shared-outstanding", Long.class, 1_000L),
                        "ISOLATED", environment.getProperty(prefix + "max-isolated-outstanding", Long.class, 100L),
                        "DEDICATED", environment.getProperty(prefix + "max-dedicated-outstanding", Long.class, 100L)),
                tokens == null ? null : Long.valueOf(tokens),
                cost == null ? null : new java.math.BigDecimal(cost),
                new RunAdmissionPolicy.PlacementCapabilities(
                        placementValues(environment.getProperty(prefix + "security-zones")),
                        placementValues(environment.getProperty(prefix + "data-residencies")),
                        placementValues(environment.getProperty(prefix + "runtime-classes")),
                        placementValues(environment.getProperty(prefix + "image-classes"))),
                RunAdmissionPolicy.OverloadOutcome.valueOf(environment.getProperty(prefix + "overload-outcome", "REJECTED")));
    }

    private static java.util.Set<String> placementValues(String value) {
        if (value == null || value.isBlank()) return java.util.Set.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Bean
    @ConditionalOnMissingBean
    RunAdmissionObserver agentWorksRunAdmissionObserver(
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registries) {
        var registry = registries.getIfAvailable();
        if (registry == null) return RunAdmissionObserver.NONE;
        return (outcome, code, placement) -> registry.counter("agentworks.run.admission.decisions",
                "outcome", outcome, "code", code, "placement", placement == null ? "unknown" : placement).increment();
    }

    @Bean
    @ConditionalOnMissingBean
    RunAdmissionService agentWorksRunAdmissionService(AgentWorksPlanes planes, LifecycleRetry retry,
            ObjectProvider<IdempotencyDigests> digests, DurableRunEngine engine, RunLimitsPolicy limits,
            RunAdmissionPolicy admissionPolicy, RunAdmissionObserver admissionObserver) {
        var run = planes.run();
        return new RunAdmissionService(run.tenants(), run.lifecycle(), run.runs(), run.clock(), retry,
                digests.getIfAvailable(), run.runStore(), engine, limits, admissionPolicy, admissionObserver);
    }

    @Bean
    @ConditionalOnMissingBean
    SessionDeliveryCoordinator agentWorksSessionDeliveryCoordinator(AgentWorksPlanes planes, LifecycleRetry retry,
            RunAdmissionService admissions, DurableRunEngine engine, JdbcSessionInboxAuthority inbox) {
        return new SessionDeliveryCoordinator(planes.run().transactions(), retry, admissions, engine, inbox);
    }

    @Bean
    @ConditionalOnMissingBean
    DurableChildRunCoordinator agentWorksDurableChildRunCoordinator(AgentWorksPlanes planes, LifecycleRetry retry,
            DurableRunEngine engine, RunAdmissionService admissions, JdbcChildRunAuthority children) {
        return new DurableChildRunCoordinator(planes.run().transactions(), retry, engine, admissions, children,
                DurableChildRunCoordinator.Policy.defaults());
    }

    @Bean
    @ConditionalOnMissingBean
    ScheduledWakeupCoordinator agentWorksScheduledWakeupCoordinator(AgentWorksPlanes planes, LifecycleRetry retry,
            DurableRunEngine engine, JdbcChildRunAuthority children, JdbcSessionInboxAuthority inbox) {
        return new ScheduledWakeupCoordinator(planes.run().transactions(), retry, engine, children, inbox);
    }

    @Bean
    @ConditionalOnMissingBean
    AgentAsTool agentWorksAgentAsTool(DurableChildRunCoordinator children, DurableRunEngine engine) {
        return new AgentAsTool(children, engine);
    }

    @Bean
    @ConditionalOnMissingBean
    ChildRunWakeupReconciliationLifecycle agentWorksChildRunWakeupReconciliation(
            DurableChildRunCoordinator children, ScheduledWakeupCoordinator wakeups,
            JdbcChildRunAuthority authority, Environment environment) {
        Duration interval = AgentWorksRunWorkerAutoConfiguration.duration(environment,
                "agentworks.children.reconciliation-interval",
                ChildRunWakeupReconciliationLifecycle.DEFAULT_INTERVAL);
        int batchSize = environment.getProperty("agentworks.children.reconciliation-batch-size", Integer.class, 256);
        return new ChildRunWakeupReconciliationLifecycle(children, wakeups, authority, interval, batchSize);
    }

    @Bean
    @ConditionalOnMissingBean
    ExposureRateLimiter agentWorksExposureRateLimiter() {
        return new FixedWindowExposureRateLimiter();
    }

    @Bean
    @ConditionalOnMissingBean
    ExposureRevisionSource agentWorksExposureRevisionSource(AgentWorksPlanes planes) {
        var run = planes.run();
        return (workspace, deploymentId, revisionId) -> run.tenants().execute(workspace, () -> {
            var deployment = run.lifecycle().findDeployment(deploymentId)
                    .orElseThrow(() -> new ExposureException("EXPOSURE_NOT_FOUND", "deployment does not exist"));
            UUID selected = revisionId == null ? deployment.currentRevisionId() : revisionId;
            if (selected == null) throw new ExposureException("EXPOSURE_NOT_FOUND", "deployment has no active revision");
            var revision = run.lifecycle().findRevision(selected)
                    .filter(value -> value.deploymentId().equals(deploymentId))
                    .orElseThrow(() -> new ExposureException("EXPOSURE_NOT_FOUND", "deployment revision does not exist"));
            var cohort = run.lifecycle().findCohortForRevision(selected)
                    .orElseThrow(() -> new ExposureException("EXPOSURE_NOT_FOUND", "deployment revision has no cohort"));
            boolean eligible = revisionId == null ? "ROUTABLE".equals(cohort.state())
                    : java.util.Set.of("ROUTABLE", "DRAINING", "QUIESCENT").contains(cohort.state());
            if (!eligible) throw new ExposureException("EXPOSURE_UNAVAILABLE",
                    "the deployment lifecycle does not permit this exposure");
            return new ExposureRevisionSource.RevisionConfiguration(revision.id(), revision.configurationJson());
        });
    }

    @Bean
    @ConditionalOnMissingBean
    AgentExposureCoordinator agentWorksAgentExposureCoordinator(ObjectProvider<InteractionChannel> channels,
            ExposureRevisionSource revisions, ExposureRateLimiter rates, JdbcSessionInboxAuthority inbox,
            SessionDeliveryCoordinator deliveries, DurableRunEngine engine) {
        return new AgentExposureCoordinator(channels.orderedStream().toList(), revisions, rates, inbox, deliveries, engine);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(IdempotencyDigests.class)
    OperationCallbackTokens agentWorksOperationCallbackTokens(IdempotencyDigests digests) {
        return new OperationCallbackTokens(digests);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(OperationCallbackTokens.class)
    DurableOperationCoordinator agentWorksDurableOperationCoordinator(AgentWorksPlanes planes, LifecycleRetry retry,
            DurableRunEngine engine, JdbcDurableOperationAuthority operations, JdbcSessionInboxAuthority inbox,
            ObjectProvider<DurableOperationProvider> providers, OperationCallbackTokens callbackTokens,
            Environment environment) {
        return new DurableOperationCoordinator(planes.run().transactions(), retry, engine, operations, inbox,
                providers.orderedStream().toList(), callbackTokens,
                environment.getProperty("agentworks.operations.callback-base-url", "http://localhost:8080"));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DurableOperationCoordinator.class)
    DurableOperationReconciliationLifecycle agentWorksDurableOperationReconciliation(
            DurableOperationCoordinator coordinator, JdbcDurableOperationAuthority operations,
            Environment environment,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registries) {
        Duration interval = AgentWorksRunWorkerAutoConfiguration.duration(environment,
                "agentworks.operations.reconciliation-interval",
                DurableOperationReconciliationLifecycle.DEFAULT_INTERVAL);
        int batchSize = environment.getProperty("agentworks.operations.reconciliation-batch-size",
                Integer.class, 256);
        return new DurableOperationReconciliationLifecycle(coordinator, operations, interval, batchSize,
                registries.getIfAvailable());
    }

    /** Run Plane convergence onto approved deployment state; it reads through the run-plane connection only. */
    @Bean
    @ConditionalOnMissingBean
    DeploymentReconciler agentWorksDeploymentReconciler(AgentWorksPlanes planes) {
        var run = planes.run();
        return new DeploymentReconciler(run.tenants(), run.lifecycle());
    }

    /** Control Plane outbox delivery; it reads the outbox and owns its cursors on the control-plane connection. */
    @Bean
    @ConditionalOnMissingBean
    ControlPlaneOutboxDispatcher agentWorksControlPlaneOutboxDispatcher(AgentWorksPlanes planes) {
        var control = planes.control();
        var dispatcher = new ControlPlaneOutboxDispatcher(control.jdbc(), control.transactions());
        // A transaction manager that refuses the dispatch's READ COMMITTED fails startup, not every dispatch (#206).
        dispatcher.verifyIsolation();
        return dispatcher;
    }

    /** Polls durable hints continuously; notification loss never becomes a correctness dependency (#44). */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agentworks.recovery.outbox", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    OutboxReconciliationLifecycle agentWorksOutboxReconciliationLifecycle(ControlPlaneOutboxDispatcher dispatcher,
            DeploymentReconciler reconciler, Environment environment,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registries,
            ObjectProvider<com.dvarahq.agentworks.runplane.compiled.CompiledReleaseCache> compiledReleases) {
        String prefix = "agentworks.recovery.outbox.";
        var retry = new ControlPlaneOutboxDispatcher.RetryPolicy(
                duration(environment, prefix + "initial-backoff",
                        ControlPlaneOutboxDispatcher.RetryPolicy.DEFAULT.initialBackoff()),
                duration(environment, prefix + "max-backoff",
                        ControlPlaneOutboxDispatcher.RetryPolicy.DEFAULT.maxBackoff()),
                environment.getProperty(prefix + "max-attempts", Integer.class,
                        ControlPlaneOutboxDispatcher.RetryPolicy.DEFAULT.maxAttempts()));
        return new OutboxReconciliationLifecycle(dispatcher, reconciler,
                environment.getProperty(prefix + "consumer", OutboxReconciliationLifecycle.DEFAULT_CONSUMER),
                duration(environment, prefix + "interval", OutboxReconciliationLifecycle.DEFAULT_INTERVAL),
                environment.getProperty(prefix + "batch-size", Integer.class,
                        OutboxReconciliationLifecycle.DEFAULT_BATCH_SIZE), retry, registries.getIfAvailable(),
                compiledReleases.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(name = "agentWorksOutboxRecoveryHealthIndicator")
    @ConditionalOnBean(OutboxReconciliationLifecycle.class)
    OutboxRecoveryHealthIndicator agentWorksOutboxRecoveryHealthIndicator(ControlPlaneOutboxDispatcher dispatcher,
            OutboxReconciliationLifecycle lifecycle) {
        return new OutboxRecoveryHealthIndicator(dispatcher, lifecycle.consumer());
    }

    /** Re-observes every deployment once when the Run Plane starts (#175); on by default. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agentworks.runs.startup-reconciliation", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    RunPlaneStartupReconciliation agentWorksRunPlaneStartupReconciliation(ControlPlaneOutboxDispatcher dispatcher,
            DeploymentReconciler reconciler) {
        return new RunPlaneStartupReconciliation(dispatcher, reconciler);
    }

    /**
     * Stream settings. A stream re-checks its session without refreshing the session's activity time, so the client's
     * reconnect (ordinary session activity) must arrive before the session could idle out: startup fails unless
     * {@code max-duration}, plus the session touch interval, plus {@code client-retry} (how long the client waits
     * after the stream's {@code reconnect} before it reconnects, #186) is below the session idle timeout. A longer
     * limit-refusal retry needs no allowance: it follows a refused reconnect, which is itself session activity.
     */
    static RunEventStreams.Settings streamSettings(Environment environment) {
        RunEventStreams.Settings settings = unvalidatedStreamSettings(environment);
        var session = AgentWorksSecurityAutoConfiguration.bind(environment).session();
        if (settings.maxDuration().plus(session.touchInterval()).plus(settings.clientRetry())
                .compareTo(session.idleTimeout()) >= 0) {
            throw new IllegalStateException("agentworks.runs.streams.max-duration (" + settings.maxDuration()
                    + ") plus agentworks.security.session.touch-interval plus agentworks.runs.streams.client-retry ("
                    + settings.clientRetry() + ") must be below agentworks.security.session.idle-timeout ("
                    + session.idleTimeout() + "), so a stream's client reconnects before its session could idle out");
        }
        return settings;
    }

    private static RunEventStreams.Settings unvalidatedStreamSettings(Environment environment) {
        RunEventStreams.Settings defaults = RunEventStreams.Settings.DEFAULTS;
        String prefix = "agentworks.runs.streams.";
        return new RunEventStreams.Settings(
                environment.getProperty(prefix + "max-streams", Integer.class, defaults.maxStreams()),
                environment.getProperty(prefix + "max-streams-per-workspace", Integer.class,
                        defaults.maxStreamsPerWorkspace()),
                environment.getProperty(prefix + "max-streams-per-subject", Integer.class, defaults.maxStreamsPerSubject()),
                environment.getProperty(prefix + "max-concurrent-reads", Integer.class, defaults.maxConcurrentReads()),
                environment.getProperty(prefix + "batch-size", Integer.class, defaults.batchSize()),
                duration(environment, prefix + "poll-interval", defaults.pollInterval()),
                duration(environment, prefix + "heartbeat-interval", defaults.heartbeatInterval()),
                duration(environment, prefix + "reauthorize-interval", defaults.reauthorizeInterval()),
                duration(environment, prefix + "max-duration", defaults.maxDuration()),
                duration(environment, prefix + "client-retry", defaults.clientRetry()));
    }

    private static Duration duration(Environment environment, String name, Duration defaultValue) {
        String value = environment.getProperty(name);
        return value == null ? defaultValue : Duration.parse(value);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnBean(WorkspaceScopeResolver.class)
    static class LifecycleApi {

        @Bean
        @ConditionalOnMissingBean
        ReleaseEnvironmentController agentWorksReleaseEnvironmentController(ReleaseService releases,
                EnvironmentService environments, WorkspaceScopeResolver workspaces, AgentWorksPlanes planes,
                LifecycleRetry retry, ObjectProvider<java.time.Clock> clocks) {
            // Release and environment services write through the Control Plane connection.
            var control = planes.control();
            return new ReleaseEnvironmentController(releases, environments, workspaces, AgentWorksSecurityAutoConfiguration
                    .controlActionAudit(control.dataSource(), control.transactions(),
                            clocks.getIfAvailable(java.time.Clock::systemUTC), retry));
        }

        @Bean
        @ConditionalOnMissingBean
        ReleaseLifecycleController agentWorksReleaseLifecycleController(ReleaseLifecycleService releases,
                WorkspaceScopeResolver workspaces, AgentWorksPlanes planes, LifecycleRetry retry,
                ObjectProvider<java.time.Clock> clocks) {
            var control = planes.control();
            return new ReleaseLifecycleController(releases, workspaces, AgentWorksSecurityAutoConfiguration
                    .controlActionAudit(control.dataSource(), control.transactions(),
                            clocks.getIfAvailable(java.time.Clock::systemUTC), retry));
        }

        @Bean
        @ConditionalOnMissingBean
        ReleaseEvidenceController agentWorksReleaseEvidenceController(ReleaseEvidenceAuthority evidence,
                WorkspaceScopeResolver workspaces, AgentWorksPlanes planes, LifecycleRetry retry,
                ObjectProvider<java.time.Clock> clocks) {
            var control = planes.control();
            return new ReleaseEvidenceController(evidence, workspaces, AgentWorksSecurityAutoConfiguration
                    .controlActionAudit(control.dataSource(), control.transactions(),
                            clocks.getIfAvailable(java.time.Clock::systemUTC), retry));
        }

        @Bean
        @ConditionalOnMissingBean
        DeploymentController agentWorksDeploymentController(DeploymentLifecycleService deployments,
                WorkspaceScopeResolver workspaces, AgentWorksPlanes planes, LifecycleRetry retry,
                ObjectProvider<java.time.Clock> clocks) {
            var control = planes.control();
            return new DeploymentController(deployments, workspaces, AgentWorksSecurityAutoConfiguration
                    .controlActionAudit(control.dataSource(), control.transactions(),
                            clocks.getIfAvailable(java.time.Clock::systemUTC), retry));
        }

        @Bean
        @ConditionalOnMissingBean
        PromotionApprovalController agentWorksPromotionApprovalController(JdbcPromotionApprovalAuthority approvals,
                EnvironmentService environments, ReleaseService releases, WorkspaceScopeResolver workspaces) {
            return new PromotionApprovalController(approvals, environments, releases, workspaces);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "agentworks.recovery.outbox", name = "enabled", havingValue = "true",
                matchIfMissing = true)
        OutboxRecoveryController agentWorksOutboxRecoveryController(ControlPlaneOutboxDispatcher dispatcher,
                WorkspaceScopeResolver workspaces, OutboxReconciliationLifecycle lifecycle) {
            return new OutboxRecoveryController(dispatcher, workspaces, lifecycle);
        }

        @Bean
        @ConditionalOnMissingBean
        RunAdmissionController agentWorksRunAdmissionController(RunAdmissionService admissions,
                DurableRunEngine engine, WorkspaceScopeResolver workspaces, AgentWorksPlanes planes,
                LifecycleRetry retry) {
            // Pause requests and withdrawals of active runs are audited with the authenticated actor (#183), in the
            // run engine's own transaction on the Run Plane connection, so each commits with its record (#194). A
            // dedicated PostgreSQL Run Plane login appends through agentworks_append_run_control_audit (V17_1, V19_1).
            var run = planes.run();
            var recorder = new com.dvarahq.agentworks.identity.RunControlAuditLog(
                    new com.dvarahq.agentworks.storage.run.JdbcRunControlAudit(run.jdbc(), run.dedicated()));
            return new RunAdmissionController(admissions, engine, workspaces,
                    com.dvarahq.agentworks.identity.ControlActionAudit.transactional(run.transactions(), recorder,
                            com.dvarahq.agentworks.identity.web.CurrentPrincipal::require, retry));
        }

        @Bean
        @ConditionalOnMissingBean
        ReplayService agentWorksReplayService(DurableRunEngine engine, ObjectProvider<ReplayModelExecutor> models,
                JdbcHumanInteractionAuthority interactions, JdbcDurableOperationAuthority operations) {
            tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
            RecordedOutcomeReader outcomes = new RecordedOutcomeReader() {
                @Override
                public java.util.Optional<String> human(com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace,
                        UUID sourceRunId, UUID interactionId) {
                    return interactions.find(workspace, interactionId)
                            .filter(record -> record.request().runId().equals(sourceRunId))
                            .map(record -> json.writeValueAsString(record.response().<Object>map(value -> value)
                                    .orElseGet(() -> java.util.Map.of("status", record.status().name()))));
                }

                @Override
                public java.util.Optional<String> operation(com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace,
                        UUID sourceRunId, UUID operationId) {
                    return operations.find(workspace, operationId).filter(record -> record.runId().equals(sourceRunId))
                            .flatMap(record -> record.result().map(json::writeValueAsString));
                }
            };
            return new ReplayService(engine, models.getIfUnique(), outcomes);
        }

        @Bean
        @ConditionalOnMissingBean
        ReplayController agentWorksReplayController(ReplayService replays, WorkspaceScopeResolver workspaces) {
            return new ReplayController(replays, workspaces);
        }

        @Bean
        ReplayApiExceptionHandler agentWorksReplayApiExceptionHandler() {
            return new ReplayApiExceptionHandler();
        }

        /**
         * Run event stream delivery (issue #35), bounded by {@code agentworks.runs.streams.*}: max-streams,
         * max-streams-per-workspace, max-streams-per-subject, max-concurrent-reads, batch-size, and the ISO-8601 durations poll-interval,
         * heartbeat-interval, reauthorize-interval, max-duration, and client-retry.
         */
        @Bean
        @ConditionalOnMissingBean
        RunEventStreams agentWorksRunEventStreams(DurableRunEngine engine, Environment environment) {
            return new RunEventStreams(engine, streamSettings(environment),
                    event -> RunEventView.of(event, engine.codec().schemas()));
        }

        @Bean
        @ConditionalOnMissingBean
        RunStreamController agentWorksRunStreamController(DurableRunEngine engine, RunEventStreams streams,
                WorkspaceScopeResolver workspaces, ObjectProvider<PrincipalRevalidation> revalidation) {
            return new RunStreamController(engine, streams, workspaces,
                    revalidation.getIfAvailable(() -> PrincipalRevalidation.REFUSE));
        }

        @Bean
        @ConditionalOnMissingBean
        HumanInteractionController agentWorksHumanInteractionController(JdbcHumanInteractionAuthority interactions,
                DurableHumanInteractionCoordinator coordinator, WorkspaceScopeResolver workspaces) {
            return new HumanInteractionController(interactions, coordinator, workspaces);
        }

        @Bean
        HumanInteractionApiExceptionHandler agentWorksHumanInteractionApiExceptionHandler() {
            return new HumanInteractionApiExceptionHandler();
        }

        @Bean
        @ConditionalOnMissingBean
        SessionController agentWorksSessionController(JdbcSessionInboxAuthority inbox,
                SessionDeliveryCoordinator deliveries, WorkspaceScopeResolver workspaces) {
            return new SessionController(inbox, deliveries, workspaces);
        }

        @Bean
        SessionApiExceptionHandler agentWorksSessionApiExceptionHandler() {
            return new SessionApiExceptionHandler();
        }

        @Bean
        @ConditionalOnMissingBean
        DurableOperationController agentWorksDurableOperationController(JdbcDurableOperationAuthority operations,
                ObjectProvider<DurableOperationCoordinator> coordinator, WorkspaceScopeResolver workspaces) {
            return new DurableOperationController(operations, coordinator.getIfAvailable(), workspaces);
        }

        @Bean
        DurableOperationApiExceptionHandler agentWorksDurableOperationApiExceptionHandler() {
            return new DurableOperationApiExceptionHandler();
        }

        /**
         * Releases a run event stream only after the request thread has left every application filter (#192), so the
         * stream's thread never uses the response while a filter still does. It is registered first and mapped to every
         * request, so it wraps the whole chain whatever path the dispatcher servlet serves.
         */
        @Bean
        org.springframework.boot.web.servlet.FilterRegistrationBean<RunStreamReleaseFilter>
                agentWorksRunStreamReleaseFilter() {
            var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
                    new RunStreamReleaseFilter());
            registration.setName("agentWorksRunStreamReleaseFilter");
            registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
            registration.addUrlPatterns("/*");
            registration.setDispatcherTypes(jakarta.servlet.DispatcherType.REQUEST);
            return registration;
        }

        @Bean
        LifecycleApiExceptionHandler agentWorksLifecycleApiExceptionHandler() {
            return new LifecycleApiExceptionHandler();
        }
    }
}
