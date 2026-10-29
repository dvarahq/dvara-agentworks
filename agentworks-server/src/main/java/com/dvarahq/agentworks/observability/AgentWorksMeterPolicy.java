package com.dvarahq.agentworks.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Rejects secret/tenant identity labels and caps every dynamic AgentWorks tag dimension. */
public final class AgentWorksMeterPolicy implements MeterFilter {
    private static final Set<String> PROHIBITED = Set.of("organization", "workspace", "workspace_id", "tenant",
            "tenant_id", "run", "run_id", "trace", "trace_id", "span_id", "release_id", "deployment_id",
            "revision_id", "cohort_id", "user", "principal", "credential", "secret", "prompt", "content");
    private static final Set<String> DYNAMIC = Set.of("connection", "consumer", "executor", "provider", "route");

    private final int maximum;
    private final ConcurrentHashMap<String, Set<String>> values = new ConcurrentHashMap<>();
    private final AtomicLong denied = new AtomicLong();

    public AgentWorksMeterPolicy(int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("maximum must be positive");
        this.maximum = maximum;
    }

    @Override
    public MeterFilterReply accept(Meter.Id id) {
        if (!id.getName().startsWith("agentworks.")) return MeterFilterReply.NEUTRAL;
        for (var tag : id.getTags()) {
            String key = tag.getKey().toLowerCase(java.util.Locale.ROOT).replace('.', '_').replace('-', '_');
            String prohibited = matching(PROHIBITED, key);
            if (prohibited != null || key.endsWith("workspaceid") || key.endsWith("tenantid")
                    || key.endsWith("runid") || key.endsWith("traceid") || key.endsWith("spanid")
                    || key.endsWith("releaseid") || key.endsWith("deploymentid") || key.endsWith("revisionid")
                    || key.endsWith("cohortid")) return refuse();
            String dynamic = matching(DYNAMIC, key);
            if (dynamic != null) {
                Set<String> seen = values.computeIfAbsent(dynamic, ignored -> ConcurrentHashMap.newKeySet());
                if (!seen.contains(tag.getValue()) && seen.size() >= maximum) return refuse();
                seen.add(tag.getValue());
            }
        }
        return MeterFilterReply.NEUTRAL;
    }

    private MeterFilterReply refuse() {
        denied.incrementAndGet();
        return MeterFilterReply.DENY;
    }

    private static String matching(Set<String> candidates, String key) {
        return candidates.stream().filter(candidate -> key.equals(candidate) || key.endsWith("_" + candidate))
                .findFirst().orElse(null);
    }

    public long denied() { return denied.get(); }
}
