package com.dvarahq.agentworks.domain.agent.compiled;

import com.dvarahq.agentworks.domain.security.CredentialRef;

import java.util.List;
import java.util.Objects;

/** Minimal approved agent structure used to establish the credential boundary. */
public record CompiledAgentDefinition(
        String executorType,
        List<CredentialRef> credentialRefs
) {

    public CompiledAgentDefinition {
        Objects.requireNonNull(executorType, "executorType");
        credentialRefs = List.copyOf(credentialRefs);
    }
}
