package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.controlplane.agent.AgentDefinitionService;
import com.dvarahq.agentworks.controlplane.agent.web.AgentDefinitionController;
import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeUnavailableException;
import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.domain.identity.AuthenticationMethod;
import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.identity.web.AgentWorksAuthentication;
import com.dvarahq.agentworks.spec.AgentSpecCodec;
import com.dvarahq.agentworks.storage.DefaultTenantBootstrap;
import com.dvarahq.agentworks.storage.JdbcAgentDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentWorksAgentDefinitionAutoConfigurationTest {
    private final AutoConfigurations configurations = AutoConfigurations.of(AgentWorksJdbcAutoConfiguration.class,
            AgentWorksAgentDefinitionAutoConfiguration.class);

    private static DataSource dataSource() {
        return new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    private WebApplicationContextRunner web() {
        DataSource source = dataSource();
        return new WebApplicationContextRunner().withConfiguration(configurations)
                .withBean(DataSource.class, () -> source)
                .withBean(TransactionTemplate.class, () -> new TransactionTemplate(new JdbcTransactionManager(source)));
    }

    @Test
    void withoutJdbcOnlyTheCodecIsCreated() {
        new ApplicationContextRunner().withConfiguration(configurations).run(context -> assertThat(context)
                .hasNotFailed().hasSingleBean(AgentSpecCodec.class).doesNotHaveBean(AgentDefinitionService.class)
                .doesNotHaveBean(AgentDefinitionController.class));
    }

    @Test
    void webApplicationWithJdbcExposesTheApi() {
        web().run(context -> assertThat(context).hasNotFailed().hasSingleBean(JdbcAgentDefinitionRepository.class)
                .hasSingleBean(AgentDefinitionService.class).hasSingleBean(AgentDefinitionController.class));
    }

    @Test
    void nonWebApplicationGetsTheServiceWithoutTheController() {
        DataSource source = dataSource();
        new ApplicationContextRunner().withConfiguration(configurations).withBean(DataSource.class, () -> source)
                .withBean(TransactionTemplate.class, () -> new TransactionTemplate(new JdbcTransactionManager(source)))
                .run(context -> assertThat(context).hasSingleBean(AgentDefinitionService.class)
                        .doesNotHaveBean(AgentDefinitionController.class));
    }

    @Test
    void defaultResolverFailsClosedWithoutAnAuthenticatedPrincipal() {
        var request = new MockHttpServletRequest();
        web().withPropertyValues("agentworks.tenancy.bootstrap-default=true").run(context ->
                assertThatThrownBy(() -> context.getBean(WorkspaceScopeResolver.class).resolve(request))
                        .isInstanceOf(WorkspaceScopeUnavailableException.class));
    }

    @Test
    void defaultResolverReturnsOnlyTheAuthenticatedPrincipalsWorkspace() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Test-Workspace", UUID.randomUUID().toString());
        WorkspaceId bound = new WorkspaceId(UUID.randomUUID());
        var principal = new AuthenticatedPrincipal(UUID.randomUUID(), DefaultTenantBootstrap.DEFAULT_ORGANIZATION_ID,
                "member", bound, WorkspaceRole.VIEWER, WorkspaceRole.VIEWER.permissions(), false,
                AuthenticationMethod.API_TOKEN, UUID.randomUUID(), false);
        var withoutWorkspace = new AuthenticatedPrincipal(UUID.randomUUID(), DefaultTenantBootstrap.DEFAULT_ORGANIZATION_ID,
                "newcomer", null, null, null, false, AuthenticationMethod.SESSION, UUID.randomUUID(), false);
        web().run(context -> {
            WorkspaceScopeResolver resolver = context.getBean(WorkspaceScopeResolver.class);
            try {
                SecurityContextHolder.getContext().setAuthentication(new AgentWorksAuthentication(principal));
                // Headers, paths, and bodies never select the workspace; the authenticated binding does.
                assertThat(resolver.resolve(request)).isEqualTo(bound);
                SecurityContextHolder.getContext().setAuthentication(new AgentWorksAuthentication(withoutWorkspace));
                assertThatThrownBy(() -> resolver.resolve(request)).isInstanceOf(WorkspaceScopeUnavailableException.class);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    @Test
    void theRemovedUnauthenticatedLocalAccessCannotBeReenabled() {
        web().withPropertyValues("agentworks.tenancy.bootstrap-default=true", "agentworks.security.unsafe-local-access=true")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("unsafe-local-access was removed"));
    }

    @Test
    void applicationResolverWinsAndPropertyCanDisableTheFeature() {
        WorkspaceScopeResolver custom = request -> new WorkspaceId(UUID.randomUUID());
        web().withBean(WorkspaceScopeResolver.class, () -> custom).run(context ->
                assertThat(context.getBean(WorkspaceScopeResolver.class)).isSameAs(custom));
        web().withPropertyValues("agentworks.agents.enabled=false").run(context -> assertThat(context)
                .doesNotHaveBean(AgentSpecCodec.class).doesNotHaveBean(AgentDefinitionService.class)
                .doesNotHaveBean(AgentDefinitionController.class));
    }
}
