package com.dvarahq.agentworks.capability;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

/** Result from a source-specific transport, retaining redirect and DNS evidence for policy validation. */
public record SkillFetch(String resolvedRef, List<URI> origins, List<InetAddress> resolvedAddresses,
        SkillPackage skillPackage, String signatureDigest, String license, String provenance) {
    public SkillFetch {
        origins = List.copyOf(origins == null ? List.of() : origins);
        resolvedAddresses = List.copyOf(resolvedAddresses == null ? List.of() : resolvedAddresses);
    }
}
