package com.dvarahq.agentworks.gateway;

import java.util.Objects;

/**
 * The model identity that actually served an invocation, recorded as run provenance. When the provider reports it,
 * a difference from the pinned model is visible here for continuity analysis (#55); Core never hides it.
 *
 * @param systemFingerprint the provider's backend fingerprint, or {@code null}
 */
public record ServedModel(String model, String systemFingerprint, Source source) {

    public enum Source {
        /** Reported by the provider for this invocation. */
        PROVIDER_REPORTED,
        /** Not reported; the pinned model is the only known identity. */
        PINNED
    }

    public ServedModel {
        GatewayDescriptor.requireText(model, "model");
        Objects.requireNonNull(source, "source");
        if (source == Source.PINNED && systemFingerprint != null) {
            throw new IllegalArgumentException("a pinned-only identity has no provider fingerprint");
        }
    }
}
