package com.dvarahq.agentworks.controlplane.evidence;

import java.time.Instant;
import java.util.Objects;

/** Provider-neutral optional transparency anchor. Anchoring never replaces signature authority. */
public interface EvidenceAnchor {
    record Receipt(String provider, String bundleDigest, String receipt, Instant anchoredAt) {
        public Receipt {
            if (provider == null || provider.isBlank()) throw new IllegalArgumentException("anchor provider is required");
            if (bundleDigest == null || bundleDigest.isBlank()) throw new IllegalArgumentException("bundle digest is required");
            if (receipt == null || receipt.isBlank()) throw new IllegalArgumentException("anchor receipt is required");
            Objects.requireNonNull(anchoredAt, "anchoredAt");
        }
    }

    Receipt submit(String bundleDigest);

    boolean verify(Receipt receipt);

    static EvidenceAnchor none() {
        return new EvidenceAnchor() {
            @Override public Receipt submit(String bundleDigest) { throw new UnsupportedOperationException("no evidence anchor configured"); }
            @Override public boolean verify(Receipt receipt) { return false; }
        };
    }
}
