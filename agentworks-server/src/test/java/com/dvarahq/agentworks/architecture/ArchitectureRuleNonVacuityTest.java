package com.dvarahq.agentworks.architecture;

import com.dvarahq.agentworks.architecture.fixture.core.CoreDependingOnForbiddenBoundary;
import com.dvarahq.agentworks.architecture.fixture.executor.ExecutorWithFinalMutableRunState;
import com.dvarahq.agentworks.architecture.fixture.executor.ExecutorWithMutableSessionState;
import com.dvarahq.agentworks.domain.agent.compiled.fixture.CompiledAgentWithApiKey;
import com.dvarahq.agentworks.domain.agent.compiled.fixture.CompiledAgentWithCredentialRef;
import com.dvarahq.agentworks.domain.agent.compiled.fixture.CompiledAgentWithStringApiKeyRef;
import com.dvarahq.agentworks.architecture.fixture.core.CoreDependingOnDvaraHqAdapter;
import com.dvarahq.agentworks.domain.fixture.DomainDependingOnSpringAi;
import com.dvarahq.agentworks.gateway.SpiFixtureDependingOnSpringAiAndHttp;
import com.dvarahq.agentworks.gateway.fixture.GatewayConfigWithApiKey;
import com.dvarahq.agentworks.gateway.fixture.GatewayConfigWithCredentialRef;
import com.dvarahq.agentworks.runplane.fixture.RunPlaneSelectingConcreteAdapter;
import com.dvarahq.agentworks.runplane.fixture.RunPlaneDependingOnMutationAuthority;
import com.dvarahq.agentworks.runplane.fixture.RunPlaneWritingLifecycle;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchitectureRuleNonVacuityTest {

    private final ClassFileImporter importer = new ClassFileImporter();

    @Test
    void domainRuleDetectsSpringAiDependency() {
        JavaClasses fixtures = importer.importClasses(DomainDependingOnSpringAi.class);

        assertThrows(AssertionError.class,
                () -> DvaraArchitectureRules.DOMAIN_IS_FRAMEWORK_AND_PROVIDER_NEUTRAL.check(fixtures));
    }

    @Test
    void executorRuleDetectsMutableAndFinalContainerState() {
        JavaClasses mutableField = importer.importClasses(ExecutorWithMutableSessionState.class);
        JavaClasses finalMutableContainer = importer.importClasses(ExecutorWithFinalMutableRunState.class);

        assertThrows(AssertionError.class,
                () -> DvaraArchitectureRules.AGENT_EXECUTORS_ARE_STATELESS.check(mutableField));
        assertThrows(AssertionError.class,
                () -> DvaraArchitectureRules.AGENT_EXECUTORS_ARE_STATELESS.check(finalMutableContainer));
    }

    @Test
    void runPlaneRuleDetectsControlPlaneMutationDependency() {
        JavaClasses fixtures = importer.importClasses(RunPlaneDependingOnMutationAuthority.class);

        assertThrows(AssertionError.class,
                () -> DvaraArchitectureRules.RUN_PLANE_HAS_NO_CONTROL_MUTATION_AUTHORITY.check(fixtures));
    }

    @Test
    void runPlaneLifecycleRuleDetectsWriteCapableRepositoryDependency() {
        JavaClasses fixtures = importer.importClasses(RunPlaneWritingLifecycle.class);

        assertThrows(AssertionError.class,
                () -> DvaraArchitectureRules.RUN_PLANE_HAS_NO_LIFECYCLE_WRITE_ACCESS.check(fixtures));
    }

    @Test
    void coreRuleDetectsConfiguredForbiddenDependency() {
        JavaClasses fixtures = importer.importClasses(CoreDependingOnForbiddenBoundary.class);

        assertThrows(AssertionError.class,
                () -> DvaraArchitectureRules.noDependencyOnPackage(
                        "com.dvarahq.agentworks.architecture.fixture.forbidden..",
                        "negative boundary fixture").check(fixtures));
    }

    @Test
    void credentialRuleAllowsReferenceAndDetectsRawApiKey() {
        JavaClasses allowed = importer.importClasses(CompiledAgentWithCredentialRef.class);
        JavaClasses violation = importer.importClasses(CompiledAgentWithApiKey.class);
        JavaClasses disguisedValue = importer.importClasses(CompiledAgentWithStringApiKeyRef.class);

        assertDoesNotThrow(
                () -> DvaraArchitectureRules.COMPILED_AGENT_STRUCTURES_CONTAIN_NO_RAW_SECRET_FIELDS.check(allowed));
        assertThrows(AssertionError.class,
                () -> DvaraArchitectureRules.COMPILED_AGENT_STRUCTURES_CONTAIN_NO_RAW_SECRET_FIELDS.check(violation));
        assertThrows(AssertionError.class,
                () -> DvaraArchitectureRules.COMPILED_AGENT_STRUCTURES_CONTAIN_NO_RAW_SECRET_FIELDS.check(disguisedValue));
    }

    @Test
    void gatewaySpiRuleDetectsFrameworkAndTransportDependencies() {
        JavaClasses fixtures = importer.importClasses(SpiFixtureDependingOnSpringAiAndHttp.class);

        assertThrows(AssertionError.class,
                () -> DvaraArchitectureRules.GATEWAY_SPI_IS_PROVIDER_AND_FRAMEWORK_NEUTRAL.check(fixtures));
    }

    @Test
    void dvaraHqRuleDetectsACoreDependencyOnTheOptionalAdapter() {
        JavaClasses fixtures = importer.importClasses(CoreDependingOnDvaraHqAdapter.class);

        assertThrows(AssertionError.class,
                () -> DvaraArchitectureRules.CORE_DOES_NOT_DEPEND_ON_THE_DVARAHQ_ADAPTER.check(fixtures));
    }

    @Test
    void adapterSelectionRuleDetectsRunPlaneUseOfAConcreteAdapter() {
        JavaClasses fixtures = importer.importClasses(RunPlaneSelectingConcreteAdapter.class);

        assertThrows(AssertionError.class,
                () -> DvaraArchitectureRules.ONLY_WIRING_SELECTS_CONCRETE_GATEWAY_ADAPTERS.check(fixtures));
    }

    @Test
    void gatewaySecretRuleAllowsReferencesAndDetectsRawKeys() {
        JavaClasses allowed = importer.importClasses(GatewayConfigWithCredentialRef.class);
        JavaClasses violation = importer.importClasses(GatewayConfigWithApiKey.class);

        assertDoesNotThrow(() -> DvaraArchitectureRules.GATEWAY_TYPES_CONTAIN_NO_RAW_SECRET_FIELDS.check(allowed));
        assertThrows(AssertionError.class,
                () -> DvaraArchitectureRules.GATEWAY_TYPES_CONTAIN_NO_RAW_SECRET_FIELDS.check(violation));
    }
}
