package com.dvarahq.agentworks.runplane.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentCredentialReferencesTest {

    @Test
    void readsOnlyLogicalNamesAndOpaqueReferences() {
        var references = DeploymentCredentialReferences.read("""
                {"placement":{},"credentialReferences":{
                  "calendar.primary":"vault://workspaces/demo/calendar",
                  "crm-delegated":"oauth://connections/crm-user"
                },"policyIdentities":{},"placementConstraints":null,"exposures":[{
                  "id":"chat","type":"INTERACTION_CHANNEL"
                }]}
                """);

        assertThat(references).hasSize(2);
        assertThat(references.get("calendar.primary").id()).isEqualTo("vault://workspaces/demo/calendar");
        assertThat(references.get("crm-delegated").id()).isEqualTo("oauth://connections/crm-user");
        assertThatThrownBy(() -> references.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void malformedOrSecretBearingConfigurationFailsWithOneSafeTypedError() {
        for (String configuration : new String[] {
                "not-json-secret-canary",
                "[]",
                "{\"credentialReferences\":{},\"unexpected\":\"secret-canary\"}",
                "{\"credentialReferences\":{\"bad name\":\"vault://secret-canary\"}}",
                "{\"credentialReferences\":{\"calendar\":42}}",
                "{\"credentialReferences\":{\"calendar\":\"raw-secret-canary\"}}",
                "{\"credentialReferences\":{\"calendar\":\"vault://one\",\"calendar\":\"vault://two\"}}"
        }) {
            assertThatThrownBy(() -> DeploymentCredentialReferences.read(configuration))
                    .isInstanceOfSatisfying(RunEngineException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("INVALID_DEPLOYMENT_CONFIGURATION");
                        assertThat(failure.getMessage()).doesNotContain("secret-canary", configuration);
                    });
        }
    }
}
