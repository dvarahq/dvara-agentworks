package com.dvarahq.agentworks.capability;

/** Source adapter. Implementations must apply bounded connect/read timeouts and return the complete fetch evidence. */
@FunctionalInterface
public interface SkillTransport {
    SkillFetch fetch(SkillSource source);
}
