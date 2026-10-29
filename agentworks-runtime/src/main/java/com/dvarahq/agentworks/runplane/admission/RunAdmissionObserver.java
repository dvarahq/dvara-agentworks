package com.dvarahq.agentworks.runplane.admission;

/** Low-cardinality admission telemetry; implementations must never retain request content or identities. */
@FunctionalInterface
public interface RunAdmissionObserver {
    RunAdmissionObserver NONE = (outcome, code, placement) -> {};

    void decided(String outcome, String code, String placement);
}
