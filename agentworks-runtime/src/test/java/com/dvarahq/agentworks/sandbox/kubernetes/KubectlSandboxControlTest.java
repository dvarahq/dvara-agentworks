package com.dvarahq.agentworks.sandbox.kubernetes;

import com.dvarahq.agentworks.domain.sandbox.SandboxOwner;
import com.dvarahq.agentworks.domain.sandbox.SandboxRef;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KubectlSandboxControlTest {
    @Test
    void manifestBindsExactOwnerAndHardenedPodAndNetworkPolicy() {
        SandboxOwner owner = new SandboxOwner(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        SandboxRef reference = new SandboxRef(UUID.randomUUID(), owner);
        String manifest = new KubectlSandboxControl("sandbox-ns", "sandbox-runner")
                .manifest(reference, KubernetesSandboxTestSupport.configuration());

        assertThat(manifest)
                .contains("kind: NetworkPolicy", "policyTypes: [Ingress, Egress]", "kind: Pod",
                        "serviceAccountName: sandbox-runner", "automountServiceAccountToken: false",
                        "runAsNonRoot: true", "runAsUser: 65532", "seccompProfile: {type: RuntimeDefault}",
                        "allowPrivilegeEscalation: false", "readOnlyRootFilesystem: true",
                        "capabilities: {drop: [ALL]}", "requests: {cpu: 0.5, memory: 64Mi}",
                        "limits: {cpu: 0.5, memory: 64Mi}", KubernetesSandboxTestSupport.IMAGE,
                        "agentworks.dvara.io/sandbox-id: " + reference.value(),
                        "agentworks.dvara.io/workspace: " + owner.workspaceId(),
                        "agentworks.dvara.io/release: " + owner.releaseId(),
                        "agentworks.dvara.io/deployment-revision: " + owner.deploymentRevisionId(),
                        "agentworks.dvara.io/cohort: " + owner.cohortId())
                .doesNotContain("hostNetwork: true", "hostPID: true", "privileged: true");
    }
}
