package com.dvarahq.agentworks.reference.embedded;

import com.dvarahq.agentworks.agent.AgentExecutor;
import com.dvarahq.agentworks.sdk.extension.AgentWorksExtension;
import com.dvarahq.agentworks.sdk.extension.ProviderKind;
import com.dvarahq.agentworks.sdk.extension.ProviderRegistration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Set;

/** Minimal native Spring agent. It starts without a server, provider credential, or hidden network call. */
@SpringBootApplication
public class EmbeddedReferenceApplication {
    public static void main(String[] args) { SpringApplication.run(EmbeddedReferenceApplication.class, args); }

    @Bean AgentExecutor greetingExecutor() { return () -> "reference-greeting"; }
    @Bean AgentExecutor analysisExecutor() { return () -> "reference-analysis"; }

    @Bean AgentWorksExtension referenceExtension(List<AgentExecutor> executors) {
        return () -> executors.stream().<ProviderRegistration<?>>map(executor -> new ProviderRegistration<>(ProviderKind.EXECUTOR,
                executor.type(), AgentExecutor.class, executor, Set.of("stateless", "multi-agent"))).toList();
    }
}
