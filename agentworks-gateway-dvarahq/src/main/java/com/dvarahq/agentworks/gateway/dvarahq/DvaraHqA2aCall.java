package com.dvarahq.agentworks.gateway.dvarahq;

import com.dvarahq.agentworks.credential.CredentialUseRequest;
import com.dvarahq.agentworks.domain.external.ExternalInvocationContext;
import com.dvarahq.agentworks.gateway.CancellationSignal;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** One DvaraHQ-governed A2A JSON-RPC call with exact AgentWorks provenance and JIT credential scopes. */
public record DvaraHqA2aCall(ExternalInvocationContext context, String agentId, String rpcId, String method,
        String paramsJson, CredentialUseRequest gatewayCredential, Optional<CredentialUseRequest> delegatedCredential,
        String protocolVersion, Duration timeout, CancellationSignal cancellation) {
    public DvaraHqA2aCall {
        Objects.requireNonNull(context); required(agentId, "agentId"); required(rpcId, "rpcId");
        required(method, "method"); required(paramsJson, "paramsJson"); Objects.requireNonNull(gatewayCredential);
        delegatedCredential = Objects.requireNonNull(delegatedCredential); required(protocolVersion, "protocolVersion");
        Objects.requireNonNull(timeout); if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("timeout must be positive and at most five minutes");
        cancellation = cancellation == null ? CancellationSignal.create() : cancellation;
        if (!agentId.matches("[A-Za-z0-9._~-]{1,128}")) throw new IllegalArgumentException("invalid agentId");
        if (!protocolVersion.matches("[A-Za-z0-9._-]{1,32}")
                || !safeHeader(context.traceId()) || !safeHeader(context.invocationId())) {
            throw new IllegalArgumentException("invalid A2A header identity");
        }
        if (rpcId.length() > 256 || method.length() > 128)
            throw new IllegalArgumentException("A2A identifier is too long");
    }
    private static void required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    private static boolean safeHeader(String value) {
        if (value.length() > 256) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x21 || character > 0x7e) return false;
        }
        return true;
    }
}
