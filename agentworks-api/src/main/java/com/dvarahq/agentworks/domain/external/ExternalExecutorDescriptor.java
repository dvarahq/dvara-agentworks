package com.dvarahq.agentworks.domain.external;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Negotiated executor identity retained in run and release provenance. */
public record ExternalExecutorDescriptor(
        String executorId,
        String executorVersion,
        String framework,
        String runtime,
        String artifactDigest,
        String dependencyLockDigest,
        String protocolVersion,
        String schemaVersion,
        ExternalAssurance assurance,
        ExternalAccessMode accessMode,
        Set<ExternalCapability> capabilities,
        boolean checkpointRecoveryProven
) {
    public ExternalExecutorDescriptor {
        executorId = required(executorId, "executorId");
        executorVersion = required(executorVersion, "executorVersion");
        framework = required(framework, "framework");
        runtime = required(runtime, "runtime");
        artifactDigest = digest(artifactDigest, "artifactDigest");
        dependencyLockDigest = digest(dependencyLockDigest, "dependencyLockDigest");
        protocolVersion = required(protocolVersion, "protocolVersion");
        schemaVersion = required(schemaVersion, "schemaVersion");
        Objects.requireNonNull(assurance, "assurance");
        Objects.requireNonNull(accessMode, "accessMode");
        capabilities = Collections.unmodifiableSet(new LinkedHashSet<>(
                Objects.requireNonNull(capabilities, "capabilities")));
        if (assurance == ExternalAssurance.DURABLE && (!checkpointRecoveryProven
                || !capabilities.containsAll(Set.of(ExternalCapability.CHECKPOINT_EXPORT,
                ExternalCapability.CHECKPOINT_RESTORE)))) {
            throw new IllegalArgumentException("DURABLE assurance requires proven checkpoint export and restore");
        }
        if (accessMode == ExternalAccessMode.FRAMEWORK_DIRECT && assurance == ExternalAssurance.DURABLE) {
            throw new IllegalArgumentException("Framework-direct access cannot claim DURABLE assurance");
        }
    }

    private static String digest(String value, String name) {
        value = required(value, name);
        if (!value.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException(name + " must be sha256");
        return value;
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
