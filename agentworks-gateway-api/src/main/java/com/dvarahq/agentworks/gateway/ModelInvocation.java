package com.dvarahq.agentworks.gateway;

import com.dvarahq.agentworks.domain.security.InvocationIdentity;

import java.time.Duration;
import java.util.Objects;

/**
 * One invocation of a pinned model.
 *
 * @param identity the principal and tenant scope; its workspace must equal the pinned release's workspace, and it is
 *        the scope under which the gateway credential is authorized
 * @param timeout the whole-invocation deadline, including every streamed chunk
 * @param cancellation the caller's signal, or {@code null} when the caller will never cancel
 */
public record ModelInvocation(PinnedModel model, InvocationIdentity identity, InvocationCorrelation correlation,
        ModelRequest request, Duration timeout, CancellationSignal cancellation) {

    /** Upper bound on one invocation's deadline; longer work belongs to durable operations, not one model call. */
    public static final Duration MAX_TIMEOUT = Duration.ofHours(1);

    public ModelInvocation {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(correlation, "correlation");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout must be positive and at most " + MAX_TIMEOUT);
        }
    }
}
