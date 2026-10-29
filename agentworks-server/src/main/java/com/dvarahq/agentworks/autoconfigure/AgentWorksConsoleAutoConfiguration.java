package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.console.web.ConsoleAdvice;
import com.dvarahq.agentworks.console.web.ConsoleAgentsController;
import com.dvarahq.agentworks.console.web.ConsoleArtifactsController;
import com.dvarahq.agentworks.console.web.ConsoleApi;
import com.dvarahq.agentworks.console.web.ConsoleDeploymentsController;
import com.dvarahq.agentworks.console.web.ConsoleOriginInterceptor;
import com.dvarahq.agentworks.console.web.ConsoleProblems;
import com.dvarahq.agentworks.console.web.ConsoleRunsController;
import com.dvarahq.agentworks.console.web.ConsoleInteractionsController;
import com.dvarahq.agentworks.console.web.ConsoleOperationsController;
import com.dvarahq.agentworks.console.web.ConsoleSessionsController;
import com.dvarahq.agentworks.console.web.ConsoleSessionController;
import com.dvarahq.agentworks.console.web.ConsoleWorkspaceController;
import com.dvarahq.agentworks.console.web.ConsoleMcpController;
import com.dvarahq.agentworks.controlplane.agent.web.AgentDefinitionController;
import com.dvarahq.agentworks.controlplane.lifecycle.web.DeploymentController;
import com.dvarahq.agentworks.controlplane.lifecycle.web.ReleaseEnvironmentController;
import com.dvarahq.agentworks.controlplane.lifecycle.web.ReleaseEvidenceController;
import com.dvarahq.agentworks.controlplane.lifecycle.web.ReleaseLifecycleController;
import com.dvarahq.agentworks.controlplane.capability.web.CapabilityCatalogController;
import com.dvarahq.agentworks.evaluation.web.EvaluationController;
import com.dvarahq.agentworks.identity.web.AuthController;
import com.dvarahq.agentworks.identity.web.AdministrativeAuditController;
import com.dvarahq.agentworks.identity.web.ApiTokenController;
import com.dvarahq.agentworks.identity.web.MembershipController;
import com.dvarahq.agentworks.artifact.web.ArtifactController;
import com.dvarahq.agentworks.runplane.admission.web.RunAdmissionController;
import com.dvarahq.agentworks.runplane.interaction.web.HumanInteractionController;
import com.dvarahq.agentworks.runplane.operation.web.DurableOperationController;
import com.dvarahq.agentworks.runplane.session.web.SessionController;
import com.dvarahq.agentworks.mcp.web.McpConnectionController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/**
 * The Web Console (issue #36, ADR-021): server-rendered pages over the same REST controllers that serve
 * {@code /api/v1}. It is present whenever those controllers are, and can be switched off with
 * {@code agentworks.console.enabled=false}; its authorization rules deny its paths by default either way.
 */
@AutoConfiguration(after = {AgentWorksAgentDefinitionAutoConfiguration.class, AgentWorksLifecycleAutoConfiguration.class,
        AgentWorksSecurityAutoConfiguration.class, AgentWorksApiAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.thymeleaf.spring6.SpringTemplateEngine")
@ConditionalOnProperty(prefix = "agentworks.console", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean({AgentDefinitionController.class, ReleaseEnvironmentController.class, DeploymentController.class,
        RunAdmissionController.class, HumanInteractionController.class, AuthController.class})
public final class AgentWorksConsoleAutoConfiguration {

    @Bean
    ConsoleProblems agentWorksConsoleProblems(ApplicationContext context,
            ObjectProvider<RequestMappingHandlerAdapter> adapter) {
        return new ConsoleProblems(context, adapter);
    }

    @Bean
    ConsoleApi agentWorksConsoleApi(AgentDefinitionController agents, ReleaseEnvironmentController releases,
            DeploymentController deployments, RunAdmissionController runs, HumanInteractionController interactions,
            SessionController sessions, DurableOperationController operations, ArtifactController artifacts, AuthController auth,
            ReleaseEvidenceController evidence, ReleaseLifecycleController releaseLifecycle,
            AdministrativeAuditController administrativeAudit, EvaluationController evaluations,
            CapabilityCatalogController capabilities, MembershipController memberships, ApiTokenController tokens,
            ConsoleProblems problems) {
        return new ConsoleApi(agents, releases, deployments, runs, interactions, sessions, operations, artifacts, auth,
                evidence, releaseLifecycle, administrativeAudit, evaluations, capabilities, memberships, tokens,
                problems);
    }

    @Bean
    ConsoleSessionController agentWorksConsoleSessionController(ConsoleApi api) {
        return new ConsoleSessionController(api);
    }

    @Bean
    ConsoleAgentsController agentWorksConsoleAgentsController(ConsoleApi api) {
        return new ConsoleAgentsController(api);
    }

    @Bean
    ConsoleDeploymentsController agentWorksConsoleDeploymentsController(ConsoleApi api) {
        return new ConsoleDeploymentsController(api);
    }

    @Bean
    ConsoleRunsController agentWorksConsoleRunsController(ConsoleApi api) {
        return new ConsoleRunsController(api);
    }

    @Bean
    ConsoleInteractionsController agentWorksConsoleInteractionsController(ConsoleApi api) {
        return new ConsoleInteractionsController(api);
    }

    @Bean
    ConsoleSessionsController agentWorksConsoleSessionsController(ConsoleApi api) {
        return new ConsoleSessionsController(api);
    }

    @Bean
    ConsoleOperationsController agentWorksConsoleOperationsController(ConsoleApi api) {
        return new ConsoleOperationsController(api);
    }

    @Bean
    ConsoleArtifactsController agentWorksConsoleArtifactsController(ConsoleApi api) {
        return new ConsoleArtifactsController(api);
    }

    @Bean
    ConsoleMcpController agentWorksConsoleMcpController(McpConnectionController connections) {
        return new ConsoleMcpController(connections);
    }

    @Bean
    ConsoleWorkspaceController agentWorksConsoleWorkspaceController(ConsoleApi api) {
        return new ConsoleWorkspaceController(api);
    }

    @Bean
    ConsoleAdvice agentWorksConsoleAdvice(ConsoleApi api) {
        return new ConsoleAdvice(api);
    }

    /** Refuses a state-changing Console request that a browser sent from another site. */
    @Bean
    WebMvcConfigurer agentWorksConsoleOrigin() {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new ConsoleOriginInterceptor()).addPathPatterns("/console/**")
                        .excludePathPatterns("/console/login");
            }
        };
    }
}
