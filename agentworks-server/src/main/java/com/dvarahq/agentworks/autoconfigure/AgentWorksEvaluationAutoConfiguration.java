package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.evaluation.CustomEvaluator;
import com.dvarahq.agentworks.evaluation.EvaluationRepository;
import com.dvarahq.agentworks.evaluation.EvaluationService;
import com.dvarahq.agentworks.evaluation.EvaluatorEngine;
import com.dvarahq.agentworks.evaluation.web.EvaluationApiExceptionHandler;
import com.dvarahq.agentworks.evaluation.web.EvaluationController;
import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.storage.evaluation.JdbcEvaluationRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/** Wires the deterministic, control-plane evaluation service to Spring JDBC. */
@AutoConfiguration(after = {AgentWorksLifecycleAutoConfiguration.class, AgentWorksAgentDefinitionAutoConfiguration.class})
@ConditionalOnBean(AgentWorksPlanes.class)
public final class AgentWorksEvaluationAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    EvaluatorEngine agentWorksEvaluatorEngine(ObjectProvider<CustomEvaluator> evaluators) {
        return new EvaluatorEngine(evaluators.orderedStream().toList());
    }

    @Bean @ConditionalOnMissingBean
    EvaluationRepository agentWorksEvaluationRepository(AgentWorksPlanes planes) {
        var control = planes.control();
        return new JdbcEvaluationRepository(control.jdbc(), control.tenants());
    }

    @Bean @ConditionalOnMissingBean
    EvaluationService agentWorksEvaluationService(EvaluationRepository repository, EvaluatorEngine engine,
            ObjectProvider<Clock> clocks) {
        return new EvaluationService(repository, engine, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class EvaluationApi {
        @Bean
        @ConditionalOnMissingBean
        EvaluationController agentWorksEvaluationController(EvaluationService evaluations,
                WorkspaceScopeResolver workspaces) {
            return new EvaluationController(evaluations, workspaces);
        }

        @Bean
        EvaluationApiExceptionHandler agentWorksEvaluationApiExceptionHandler() {
            return new EvaluationApiExceptionHandler();
        }
    }
}
