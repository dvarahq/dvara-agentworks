package com.dvarahq.agentworks.sdk.extension;

import java.util.Collection;

/** ServiceLoader and Spring bean registration contract for 1.0 provider families. */
public interface AgentWorksExtension {
    Collection<ProviderRegistration<?>> providers();
}
