package com.dvarahq.agentworks.reference.embedded;

import com.dvarahq.agentworks.agent.AgentExecutor;
import com.dvarahq.agentworks.sdk.AgentWorksClient;
import com.dvarahq.agentworks.sdk.extension.AgentWorksExtension;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddedReferenceApplicationTest {
    @Test void startsOfflineWithNativeExecutorAndNoImplicitRemoteClient() {
        try (var context = new SpringApplicationBuilder(EmbeddedReferenceApplication.class)
                .web(WebApplicationType.NONE).properties("spring.main.banner-mode=off").run()) {
            assertThat(context.getBeansOfType(AgentExecutor.class).values()).extracting(AgentExecutor::type)
                    .containsExactlyInAnyOrder("reference-greeting", "reference-analysis");
            assertThat(context.getBean(AgentWorksExtension.class).providers()).hasSize(2);
            assertThat(context.getBeansOfType(AgentWorksClient.class)).isEmpty();
        }
    }
}
