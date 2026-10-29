package com.dvarahq.agentworks.runplane.admission;

import com.dvarahq.agentworks.domain.run.RunLimits;
import com.dvarahq.agentworks.storage.lifecycle.JdbcRunAdmissionRepository.AdmissionSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic, captured-input admission and bulkhead policy for one installation. */
public record RunAdmissionPolicy(String version, long maxWorkspaceOutstanding, long maxDeploymentOutstanding,
        long maxWorkspaceQueued, long maxDeploymentQueued, long maxRootsPerWindow, Duration rateWindow,
        long maxSandboxOutstanding, Map<String, Long> maxPlacementOutstanding, Long maxWorkspaceReservedTokens,
        BigDecimal maxWorkspaceReservedCostUsd, PlacementCapabilities capabilities, OverloadOutcome overloadOutcome) {

    public enum OverloadOutcome { REJECTED, DEFERRED }

    public record PlacementCapabilities(Set<String> securityZones, Set<String> dataResidencies,
            Set<String> runtimeClasses, Set<String> imageClasses) {
        public PlacementCapabilities {
            securityZones = Set.copyOf(securityZones);
            dataResidencies = Set.copyOf(dataResidencies);
            runtimeClasses = Set.copyOf(runtimeClasses);
            imageClasses = Set.copyOf(imageClasses);
        }

        boolean supports(Map<String, String> requested) {
            return supports(securityZones, requested.get("securityZone"))
                    && supports(dataResidencies, requested.get("dataResidency"))
                    && supports(runtimeClasses, requested.get("runtimeClass"))
                    && supports(imageClasses, requested.get("imageClass"));
        }

        private static boolean supports(Set<String> offered, String requested) {
            return requested == null || offered.contains(requested);
        }
    }

    public record Request(boolean root, boolean queued, boolean requiresSandbox, String placement, RunLimits limits,
            Map<String, String> placementConstraints) {
        public Request {
            Objects.requireNonNull(placement, "placement");
            Objects.requireNonNull(limits, "limits");
            placementConstraints = Map.copyOf(placementConstraints);
        }
    }

    public record Result(String outcome, String code, String detail) {
        public boolean accepted() { return "ACCEPTED".equals(outcome); }
    }

    public RunAdmissionPolicy {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(rateWindow, "rateWindow");
        Objects.requireNonNull(maxPlacementOutstanding, "maxPlacementOutstanding");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(overloadOutcome, "overloadOutcome");
        maxPlacementOutstanding = Map.copyOf(maxPlacementOutstanding);
        if (version.isBlank() || version.length() > 64 || rateWindow.isZero() || rateWindow.isNegative()
                || maxWorkspaceOutstanding < 1 || maxDeploymentOutstanding < 1 || maxWorkspaceQueued < 0
                || maxDeploymentQueued < 0 || maxRootsPerWindow < 1 || maxSandboxOutstanding < 0
                || maxPlacementOutstanding.values().stream().anyMatch(value -> value == null || value < 1)
                || maxWorkspaceReservedTokens != null && maxWorkspaceReservedTokens < 0
                || maxWorkspaceReservedCostUsd != null && maxWorkspaceReservedCostUsd.signum() < 0) {
            throw new IllegalArgumentException("admission policy bounds are invalid");
        }
        for (String placement : java.util.Set.of("SHARED", "ISOLATED", "DEDICATED")) {
            if (!maxPlacementOutstanding.containsKey(placement)) {
                throw new IllegalArgumentException("admission policy has no " + placement + " capacity");
            }
        }
    }

    public Result evaluate(AdmissionSnapshot current, Request request) {
        if (!capabilities.supports(request.placementConstraints())) {
            return new Result("REJECTED", "PLACEMENT_INCOMPATIBLE",
                    "no capacity matches the deployment placement constraints");
        }
        if (current.workspaceOutstanding() >= maxWorkspaceOutstanding) return saturated("WORKSPACE_OUTSTANDING_LIMIT");
        if (current.deploymentOutstanding() >= maxDeploymentOutstanding) return saturated("DEPLOYMENT_OUTSTANDING_LIMIT");
        if (request.queued() && current.workspaceQueued() >= maxWorkspaceQueued) return saturated("WORKSPACE_QUEUE_FULL");
        if (request.queued() && current.deploymentQueued() >= maxDeploymentQueued) return saturated("DEPLOYMENT_QUEUE_FULL");
        if (request.root() && current.rootsInWindow() >= maxRootsPerWindow) return saturated("ADMISSION_RATE_LIMITED");
        if (request.requiresSandbox() && current.sandboxOutstanding() >= maxSandboxOutstanding) {
            return saturated("SANDBOX_CAPACITY_EXHAUSTED");
        }
        Long placementLimit = maxPlacementOutstanding.get(request.placement());
        if (placementLimit == null) return new Result("REJECTED", "PLACEMENT_UNSUPPORTED", "placement is unsupported");
        if (current.placementOutstanding() >= placementLimit) return saturated("PLACEMENT_CAPACITY_EXHAUSTED");
        if (maxWorkspaceReservedTokens != null) {
            Long requested = request.limits().maxTotalTokens();
            if (requested == null) return new Result("REJECTED", "TOKEN_BUDGET_UNBOUNDED",
                    "a total-token limit is required by the workspace budget");
            if (requested > maxWorkspaceReservedTokens
                    || current.reservedTokens() > maxWorkspaceReservedTokens - requested) {
                return saturated("TOKEN_BUDGET_EXHAUSTED");
            }
        }
        if (maxWorkspaceReservedCostUsd != null) {
            BigDecimal requested = request.limits().maxCostUsd();
            if (requested == null) return new Result("REJECTED", "COST_BUDGET_UNBOUNDED",
                    "a cost limit is required by the workspace budget");
            if (current.reservedCostUsd().add(requested).compareTo(maxWorkspaceReservedCostUsd) > 0) {
                return saturated("COST_BUDGET_EXHAUSTED");
            }
        }
        return new Result("ACCEPTED", "ADMITTED", "capacity is available");
    }

    private Result saturated(String code) {
        return new Result(overloadOutcome.name(), code, "admission capacity is currently unavailable");
    }

    public static RunAdmissionPolicy defaults() {
        return new RunAdmissionPolicy("default-v1", 1_000, 100, 900, 90, 600, Duration.ofMinutes(1), 100,
                Map.of("SHARED", 1_000L, "ISOLATED", 100L, "DEDICATED", 100L), null, null,
                new PlacementCapabilities(Set.of(), Set.of(), Set.of(), Set.of()), OverloadOutcome.REJECTED);
    }
}
