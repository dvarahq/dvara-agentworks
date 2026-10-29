package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.capability.ToolEffect;
import com.dvarahq.agentworks.domain.run.RunPins;
import com.dvarahq.agentworks.domain.security.ExecutionIntent;
import com.dvarahq.agentworks.domain.security.ExecutionPolicy;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import com.dvarahq.agentworks.domain.security.RememberedPermissionEvaluator;
import com.dvarahq.agentworks.domain.security.RememberedPermissionRequest;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The deterministic policy boundary immediately before a Spring AI tool callback. It uses only immutable run facts;
 * it never resolves a credential or calls a provider. Hard identity checks have deny precedence over release policy
 * and autonomy. Version 1 through 3 releases retain their pre-policy behavior explicitly until their compatibility
 * contract is retired; version 4 effects require {@link ExecutionIntent#EXECUTE}. Version 5 additionally re-reads
 * the remembered-permission authority for the exact pinned tool effect on every use.
 */
final class ToolPolicyGate {
    enum Verdict { ALLOW, DENY }

    record PermissionDecision(String verdict, String reason, Optional<UUID> ruleId, Optional<Long> ruleVersion,
            String capability, ToolEffect effect) {
        PermissionDecision {
            Objects.requireNonNull(verdict, "verdict");
            Objects.requireNonNull(reason, "reason");
            ruleId = Objects.requireNonNull(ruleId, "ruleId");
            ruleVersion = Objects.requireNonNull(ruleVersion, "ruleVersion");
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(effect, "effect");
        }
    }

    record Decision(Verdict verdict, String reason, String intent, String autonomyProfile,
            Optional<PermissionDecision> rememberedPermission) {
        Decision {
            Objects.requireNonNull(verdict, "verdict");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(intent, "intent");
            Objects.requireNonNull(autonomyProfile, "autonomyProfile");
            rememberedPermission = Objects.requireNonNull(rememberedPermission, "rememberedPermission");
        }

        boolean allowed() {
            return verdict == Verdict.ALLOW;
        }
    }

    private static final String UNPINNED = "UNPINNED";

    private final UUID runWorkspaceId;
    private final InvocationIdentity identity;
    private final Optional<ExecutionPolicy> executionPolicy;
    private final RunPins pins;
    private final String capability;
    private final ToolEffect effect;
    private final ToolPermissionAuthority permissions;

    ToolPolicyGate(UUID runWorkspaceId, InvocationIdentity identity, Optional<ExecutionPolicy> executionPolicy) {
        this.runWorkspaceId = Objects.requireNonNull(runWorkspaceId, "runWorkspaceId");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.executionPolicy = Objects.requireNonNull(executionPolicy, "executionPolicy");
        this.pins = null;
        this.capability = null;
        this.effect = null;
        this.permissions = null;
    }

    ToolPolicyGate(RunPins pins, InvocationIdentity identity, Optional<ExecutionPolicy> executionPolicy,
            String capability, ToolEffect effect, ToolPermissionAuthority permissions) {
        this.pins = Objects.requireNonNull(pins, "pins");
        this.runWorkspaceId = pins.workspaceId();
        this.identity = Objects.requireNonNull(identity, "identity");
        this.executionPolicy = Objects.requireNonNull(executionPolicy, "executionPolicy");
        this.capability = exact(capability, "capability");
        this.effect = Objects.requireNonNull(effect, "effect");
        this.permissions = permissions;
    }

    Decision evaluate() {
        String intent = executionPolicy.map(policy -> policy.initialIntent().name()).orElse(UNPINNED);
        String autonomy = executionPolicy.map(policy -> policy.autonomyProfile().name()).orElse(UNPINNED);
        if (!runWorkspaceId.equals(identity.workspaceId())) {
            return decided(Verdict.DENY, "WORKSPACE_MISMATCH", intent, autonomy);
        }
        // Core 1.0 has no delegated execution contract. A different delegated subject is therefore never inferred to
        // be authorized, regardless of intent, autonomy, or a later remembered permission.
        if (!identity.userId().equals(identity.delegatedSubjectId())) {
            return decided(Verdict.DENY, "DELEGATED_SUBJECT_MISMATCH", intent, autonomy);
        }
        if (executionPolicy.isEmpty()) {
            return decided(Verdict.ALLOW, "LEGACY_MANIFEST_COMPATIBILITY", intent, autonomy);
        }
        if (executionPolicy.get().initialIntent() != ExecutionIntent.EXECUTE) {
            return decided(Verdict.DENY, "EXECUTION_INTENT_DENIED", intent, autonomy);
        }
        if (effect == null) return decided(Verdict.ALLOW, "PINNED_POLICY_ALLOWED", intent, autonomy);
        if (permissions == null) {
            return permission(Verdict.DENY, "REMEMBERED_PERMISSION_AUTHORITY_UNAVAILABLE", intent, autonomy,
                    "DENY", "AUTHORITY_UNAVAILABLE", Optional.empty(), Optional.empty());
        }
        RememberedPermissionEvaluator.Decision permission;
        try {
            permission = permissions.evaluate(new RememberedPermissionRequest(identity.organizationId(),
                    identity.workspaceId(), identity.userId(), pins.definitionId(), pins.releaseId(), capability,
                    effect.resource(), effect.action(), pins.environmentId(), effect.risk(), Instant.EPOCH));
        } catch (RuntimeException unavailable) {
            return permission(Verdict.DENY, "REMEMBERED_PERMISSION_AUTHORITY_UNAVAILABLE", intent, autonomy,
                    "DENY", "AUTHORITY_UNAVAILABLE", Optional.empty(), Optional.empty());
        }
        Verdict verdict = permission.verdict() == RememberedPermissionEvaluator.Verdict.ALLOW
                ? Verdict.ALLOW : Verdict.DENY;
        return permission(verdict, "REMEMBERED_PERMISSION_" + permission.reason().name(), intent, autonomy,
                permission.verdict().name(), permission.reason().name(), permission.ruleId(), permission.ruleVersion());
    }

    private Decision decided(Verdict verdict, String reason, String intent, String autonomy) {
        return new Decision(verdict, reason, intent, autonomy, Optional.empty());
    }

    private Decision permission(Verdict verdict, String reason, String intent, String autonomy,
            String permissionVerdict, String permissionReason, Optional<UUID> ruleId, Optional<Long> ruleVersion) {
        return new Decision(verdict, reason, intent, autonomy, Optional.of(new PermissionDecision(permissionVerdict,
                permissionReason, ruleId, ruleVersion, capability, effect)));
    }

    private static String exact(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(name + " must be a non-blank exact value");
        }
        return value;
    }
}
