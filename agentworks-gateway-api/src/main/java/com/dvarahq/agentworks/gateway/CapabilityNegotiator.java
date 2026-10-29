package com.dvarahq.agentworks.gateway;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Negotiates the capabilities an invocation needs against what an adapter advertises, under a captured policy. It
 * runs before any credential resolution or provider contact, so an unsupported required capability costs nothing.
 */
public final class CapabilityNegotiator {

    private CapabilityNegotiator() {
    }

    /** The negotiated outcome: capabilities the adapter will use natively and the degradations Core applies. */
    public record Negotiation(Set<GatewayCapability> granted, List<DegradedBehavior> degradations) {
        public Negotiation {
            granted = Collections.unmodifiableSet(granted.isEmpty() ? EnumSet.noneOf(GatewayCapability.class)
                    : EnumSet.copyOf(granted));
            degradations = List.copyOf(degradations);
        }

        public boolean granted(GatewayCapability capability) {
            return granted.contains(capability);
        }

        public boolean degraded(GatewayCapability capability) {
            return degradations.stream().anyMatch(behavior -> behavior.capability() == capability);
        }
    }

    /**
     * @param needed capabilities this invocation uses (a subset of the negotiated capabilities)
     * @param advertised what the adapter currently advertises
     * @param policy the release-captured policy
     * @throws GatewayException {@link GatewayErrorCode#CAPABILITY_UNSUPPORTED} naming every missing required capability
     */
    public static Negotiation negotiate(Set<GatewayCapability> needed, Set<GatewayCapability> advertised,
            CapabilityPolicy policy) {
        Objects.requireNonNull(needed, "needed");
        Objects.requireNonNull(advertised, "advertised");
        Objects.requireNonNull(policy, "policy");
        EnumSet<GatewayCapability> granted = EnumSet.noneOf(GatewayCapability.class);
        List<DegradedBehavior> degradations = new ArrayList<>();
        EnumSet<GatewayCapability> missingRequired = EnumSet.noneOf(GatewayCapability.class);
        for (GatewayCapability capability : needed.stream().sorted().toList()) {
            if (!capability.negotiated()) {
                throw new IllegalArgumentException(capability + " is not a negotiated capability");
            }
            if (advertised.contains(capability)) {
                granted.add(capability);
            } else if (policy.modeFor(capability) == CapabilityMode.DEGRADE) {
                degradations.add(DegradedBehavior.forCapability(capability));
            } else {
                missingRequired.add(capability);
            }
        }
        if (!missingRequired.isEmpty()) {
            throw new GatewayException(GatewayErrorCode.CAPABILITY_UNSUPPORTED, "required capabilities unsupported by "
                    + "the gateway: " + missingRequired.stream().map(Enum::name).collect(Collectors.joining(", ")));
        }
        return new Negotiation(granted, degradations);
    }
}
