package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.api.web.ApiFallbackExceptionHandler;
import com.dvarahq.agentworks.api.web.OpenApiController;
import com.dvarahq.agentworks.artifact.ArtifactLifecycle;
import com.dvarahq.agentworks.artifact.ArtifactStore;
import com.dvarahq.agentworks.artifact.web.ArtifactApiExceptionHandler;
import com.dvarahq.agentworks.artifact.web.ArtifactController;
import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.identity.ControlActionAudit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The cross-cutting {@code /api/v1} contract (issue #35): the one problem model for every exception a
 * controller-scoped handler leaves unhandled, and the published OpenAPI description.
 */
@AutoConfiguration(beforeName = "org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class AgentWorksApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ApiFallbackExceptionHandler agentWorksApiFallbackExceptionHandler() {
        return new ApiFallbackExceptionHandler();
    }

    /** Replaces Spring Boot's default error body, which echoes the request path, with the problem model. */
    @Bean
    @ConditionalOnMissingBean(org.springframework.boot.webmvc.error.ErrorController.class)
    com.dvarahq.agentworks.api.web.ApiErrorController agentWorksApiErrorController() {
        return new com.dvarahq.agentworks.api.web.ApiErrorController();
    }

    @Bean
    @ConditionalOnMissingBean
    OpenApiController agentWorksOpenApiController() {
        return new OpenApiController();
    }

    @Bean
    @ConditionalOnMissingBean
    ArtifactController agentWorksArtifactController(ObjectProvider<ArtifactStore> stores,
            ObjectProvider<ArtifactLifecycle> lifecycles, WorkspaceScopeResolver workspaces,
            ObjectProvider<ControlActionAudit> audits) {
        return new ArtifactController(stores, lifecycles, workspaces, audits);
    }

    @Bean
    @ConditionalOnMissingBean
    ArtifactApiExceptionHandler agentWorksArtifactApiExceptionHandler() {
        return new ArtifactApiExceptionHandler();
    }

    /** Keeps problem documents free of the request path Spring would otherwise add as their instance. */
    @Bean
    @ConditionalOnMissingBean
    com.dvarahq.agentworks.api.web.ProblemInstanceAdvice agentWorksProblemInstanceAdvice() {
        return new com.dvarahq.agentworks.api.web.ProblemInstanceAdvice();
    }

    /**
     * Replaces Tomcat's HTML error report, used for requests rejected before any application code runs, with the
     * problem model. It runs after Spring Boot's own customizer, whose error report valve it replaces.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.apache.catalina.valves.ErrorReportValve")
    static class TomcatErrorReport {
        @Bean
        org.springframework.boot.web.server.WebServerFactoryCustomizer<org.springframework.boot.tomcat.ConfigurableTomcatWebServerFactory>
                agentWorksProblemErrorReport() {
            return new ProblemErrorReport();
        }
    }

    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
    static final class ProblemErrorReport implements org.springframework.boot.web.server.WebServerFactoryCustomizer<
            org.springframework.boot.tomcat.ConfigurableTomcatWebServerFactory>, org.springframework.core.Ordered {
        @Override
        public void customize(org.springframework.boot.tomcat.ConfigurableTomcatWebServerFactory factory) {
            factory.addContextCustomizers(context -> {
                // The host adds its default report valve at start unless one of exactly this class is present.
                if (context.getParent() instanceof org.apache.catalina.core.StandardHost host) {
                    host.setErrorReportValveClass(com.dvarahq.agentworks.api.web.ProblemErrorReportValve.class.getName());
                }
                var pipeline = context.getParent().getPipeline();
                for (var valve : pipeline.getValves()) {
                    if (valve instanceof org.apache.catalina.valves.ErrorReportValve
                            && !(valve instanceof com.dvarahq.agentworks.api.web.ProblemErrorReportValve)) {
                        pipeline.removeValve(valve);
                    }
                }
                pipeline.addValve(new com.dvarahq.agentworks.api.web.ProblemErrorReportValve());
            });
        }

        @Override
        public int getOrder() {
            return org.springframework.core.Ordered.LOWEST_PRECEDENCE;
        }
    }
}
