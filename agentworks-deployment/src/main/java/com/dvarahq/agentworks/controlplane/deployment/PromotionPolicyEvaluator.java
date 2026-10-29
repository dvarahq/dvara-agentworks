package com.dvarahq.agentworks.controlplane.deployment;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleJson;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ContinuityAction;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ContinuityResult;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.DependencyDrift;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.DependencyType;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.GateResult;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.GateType;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.NumericComparison;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionEvidence;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionGate;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionPolicy;
import com.dvarahq.agentworks.storage.lifecycle.Digests;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Pure deterministic gate and dependency-continuity evaluation over captured inputs. */
final class PromotionPolicyEvaluator {
    private PromotionPolicyEvaluator() {}

    static List<GateResult> evaluate(PromotionPolicy policy, UUID releaseId, String actor,
            List<PromotionEvidence> evidence, Function<String, Boolean> priorEnvironment) {
        Map<LifecycleModel.EvidenceKind, PromotionEvidence> byKind = new EnumMap<>(LifecycleModel.EvidenceKind.class);
        for (PromotionEvidence item : evidence) {
            if (item == null || item.id() == null || item.kind() == null || !releaseId.equals(item.releaseId())
                    || item.digest() == null || !item.digest().matches("sha256:[0-9a-f]{64}")) {
                continue;
            }
            byKind.putIfAbsent(item.kind(), item);
        }
        List<GateResult> results = new ArrayList<>();
        for (PromotionGate gate : policy.gates()) {
            if (gate.type() == GateType.CHANGE_FREEZE) {
                results.add(failed(gate, "the environment is under a change freeze"));
            } else if (gate.type() == GateType.REQUIRE_PRIOR_ENVIRONMENT) {
                boolean passed = priorEnvironment.apply(gate.environment());
                results.add(new GateResult(gate, passed ? "PASSED" : "FAILED", passed
                        ? "the release was deployed in the prior environment"
                        : "the release has not been deployed in the prior environment"));
            } else {
                LifecycleModel.EvidenceKind kind = evidenceKind(gate);
                PromotionEvidence item = byKind.get(kind);
                if (item == null) {
                    results.add(failed(gate, "required immutable evidence is missing"));
                    continue;
                }
                String failure = evidenceFailure(gate, item, actor);
                results.add(new GateResult(gate, failure == null ? "PASSED" : "FAILED",
                        failure == null ? "captured evidence satisfies the gate" : failure, item.id(), item.digest()));
            }
        }
        return List.copyOf(results);
    }

    private static LifecycleModel.EvidenceKind evidenceKind(PromotionGate gate) {
        if (gate.type() == GateType.SEPARATION_OF_DUTIES) return LifecycleModel.EvidenceKind.HUMAN_APPROVAL;
        if (gate.evidenceKind() != null) return gate.evidenceKind();
        return LifecycleModel.EvidenceKind.valueOf(gate.type().name());
    }

    private static String evidenceFailure(PromotionGate gate, PromotionEvidence evidence, String actor) {
        if (gate.requiredStatus() != null && !gate.requiredStatus().equals(evidence.status())) {
            return "evidence status is " + evidence.status() + ", required " + gate.requiredStatus();
        }
        if (gate.type() == GateType.SEPARATION_OF_DUTIES
                && (stableSubject(evidence.actor()) == null
                        || Objects.equals(stableSubject(actor), stableSubject(evidence.actor())))) {
            return "approval must be captured from an actor other than the promoter";
        }
        if (gate.metric() != null) {
            BigDecimal actual = evidence.metrics().get(gate.metric());
            if (actual == null) return "required metric " + gate.metric() + " is missing";
            int comparison = actual.compareTo(gate.threshold());
            if (gate.comparison() == NumericComparison.AT_MOST && comparison > 0) {
                return gate.metric() + " exceeds " + gate.threshold().toPlainString();
            }
            if (gate.comparison() == NumericComparison.AT_LEAST && comparison < 0) {
                return gate.metric() + " is below " + gate.threshold().toPlainString();
            }
        }
        return null;
    }

    /** Credential/session suffixes are audit detail, never a distinct human for separation-of-duties. */
    static String stableSubject(String actor) {
        if (actor == null) return null;
        if (actor.startsWith("approval:")) actor = actor.substring("approval:".length());
        if (actor.matches("^user:[0-9a-fA-F-]{36}(?:/.*)?$")) {
            return actor.substring(0, Math.min(actor.length(), "user:".length() + 36)).toLowerCase(java.util.Locale.ROOT);
        }
        return actor;
    }

    static ContinuityResult continuity(PromotionPolicy policy, String previousManifest, String candidateManifest,
            Map<DependencyType, Object> current) {
        Map<DependencyType, Object> approved = dependencies(candidateManifest);
        Map<DependencyType, Object> previous = previousManifest == null ? Map.of() : dependencies(previousManifest);
        List<DependencyDrift> drift = new ArrayList<>();
        for (DependencyType type : DependencyType.values()) {
            String approvedDigest = Digests.prefixed(LifecycleJson.write(approved.get(type)));
            String currentDigest = Digests.prefixed(LifecycleJson.write(current.get(type)));
            boolean availabilityDrift = !approvedDigest.equals(currentDigest);
            String priorDigest = previousManifest == null ? approvedDigest
                    : Digests.prefixed(LifecycleJson.write(previous.get(type)));
            boolean releaseDrift = previousManifest != null && !priorDigest.equals(approvedDigest);
            if (availabilityDrift || releaseDrift) {
                drift.add(new DependencyDrift(type, releaseDrift ? priorDigest : approvedDigest, currentDigest));
            }
        }
        String state = drift.isEmpty() ? (previousManifest == null ? "INITIAL" : "UNCHANGED")
                : policy.continuityAction() == ContinuityAction.FAIL ? "FAILED" : "DEGRADED";
        return new ContinuityResult(state, policy.continuityAction(), drift);
    }

    static ContinuityResult continuity(PromotionPolicy policy, String previousManifest, String candidateManifest) {
        return continuity(policy, previousManifest, candidateManifest, dependencies(candidateManifest));
    }

    static Map<DependencyType, Object> dependencies(String manifest) {
        return dependencies(LifecycleJson.read(manifest, Map.class));
    }

    private static Map<DependencyType, Object> dependencies(Map<?, ?> manifest) {
        Map<DependencyType, Object> values = new EnumMap<>(DependencyType.class);
        values.put(DependencyType.MODEL, value(manifest, "models"));
        values.put(DependencyType.TOOL, list(manifest, "capabilityPacks"));
        values.put(DependencyType.MCP, Map.of());
        values.put(DependencyType.SKILL, list(manifest, "skills"));
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("execution", value(manifest, "execution"));
        policy.put("context", value(manifest, "context"));
        policy.put("memory", value(manifest, "memory"));
        policy.put("limits", value(manifest, "limits"));
        values.put(DependencyType.POLICY, policy);
        Object executor = value(manifest, "executor");
        values.put(DependencyType.CHILD, executor instanceof Map<?, ?> map && map.get("external") != null
                ? map.get("external") : Map.of());
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("executor", value(manifest, "executor"));
        runtime.put("sandbox", value(manifest, "sandbox"));
        values.put(DependencyType.RUNTIME, runtime);
        return values;
    }

    private static Object value(Map<?, ?> map, String key) {
        return map.get(key) == null ? Map.of() : map.get(key);
    }

    private static Object list(Map<?, ?> map, String key) {
        return map.get(key) == null ? List.of() : map.get(key);
    }

    private static GateResult failed(PromotionGate gate, String detail) {
        return new GateResult(gate, "FAILED", detail);
    }
}
