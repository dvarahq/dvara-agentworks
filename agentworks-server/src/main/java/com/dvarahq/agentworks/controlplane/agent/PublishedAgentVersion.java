package com.dvarahq.agentworks.controlplane.agent;

import com.dvarahq.agentworks.spec.AgentVersion;

/** Publication outcome; {@code created} is false when identical content was already published under the label. */
public record PublishedAgentVersion(AgentVersion version, boolean created) {
}
