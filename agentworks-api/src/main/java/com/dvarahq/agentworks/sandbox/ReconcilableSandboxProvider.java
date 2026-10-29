package com.dvarahq.agentworks.sandbox;

import com.dvarahq.agentworks.domain.sandbox.SandboxRef;
import com.dvarahq.agentworks.domain.sandbox.SandboxProvisionRequest;

import java.util.Set;

/**
 * Provider control surface for rebuilding resource truth after process loss.
 *
 * <p>Discovery returns only resources carrying the provider's complete, valid AgentWorks ownership identity. Probe and
 * retirement must compare every immutable owner pin before acting, so a durable record can never adopt or delete a
 * resource belonging to another tenant, run, release, revision, or cohort.</p>
 */
public interface ReconcilableSandboxProvider extends SandboxProvider {
    /** Provisioning a request with a reserved identity must return that exact identity or fail before allocation. */
    @Override
    SandboxInstance provision(SandboxProvisionRequest request);

    Set<SandboxRef> discoverOwnedSandboxes();

    boolean isPresent(SandboxRef expected);

    void retire(SandboxRef expected);
}
