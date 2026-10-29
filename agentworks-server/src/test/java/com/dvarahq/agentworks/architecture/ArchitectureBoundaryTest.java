package com.dvarahq.agentworks.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureBoundaryTest {

    private final JavaClasses productionClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.dvarahq.agentworks");

    @Test
    void domainIsFrameworkAndProviderNeutral() {
        DvaraArchitectureRules.DOMAIN_IS_FRAMEWORK_AND_PROVIDER_NEUTRAL.check(productionClasses);
    }

    @Test
    void agentExecutorsAreStateless() {
        DvaraArchitectureRules.AGENT_EXECUTORS_ARE_STATELESS.check(productionClasses);
    }

    @Test
    void runPlaneHasNoControlPlaneMutationAuthority() {
        DvaraArchitectureRules.RUN_PLANE_HAS_NO_CONTROL_MUTATION_AUTHORITY.check(productionClasses);
    }

    @Test
    void runPlaneHasNoLifecycleWriteAccess() {
        DvaraArchitectureRules.RUN_PLANE_HAS_NO_LIFECYCLE_WRITE_ACCESS.check(productionClasses);
    }

    @Test
    void coreHasNoEnterpriseDependency() {
        DvaraArchitectureRules.CORE_HAS_NO_ENTERPRISE_DEPENDENCY.check(productionClasses);
    }

    @Test
    void compiledAgentStructuresContainCredentialReferencesOnly() {
        DvaraArchitectureRules.COMPILED_AGENT_STRUCTURES_CONTAIN_NO_RAW_SECRET_FIELDS.check(productionClasses);
    }

    @Test
    void gatewaySpiIsProviderAndFrameworkNeutral() {
        DvaraArchitectureRules.GATEWAY_SPI_IS_PROVIDER_AND_FRAMEWORK_NEUTRAL.check(productionClasses);
    }

    @Test
    void gatewayAdaptersUseNoProviderSdk() {
        DvaraArchitectureRules.GATEWAY_ADAPTERS_USE_NO_PROVIDER_SDK.check(productionClasses);
    }

    @Test
    void coreDoesNotDependOnTheDvaraHqAdapter() {
        DvaraArchitectureRules.CORE_DOES_NOT_DEPEND_ON_THE_DVARAHQ_ADAPTER.check(productionClasses);
    }

    @Test
    void onlyWiringSelectsConcreteGatewayAdapters() {
        DvaraArchitectureRules.ONLY_WIRING_SELECTS_CONCRETE_GATEWAY_ADAPTERS.check(productionClasses);
    }

    @Test
    void gatewayTypesContainNoRawSecretFields() {
        DvaraArchitectureRules.GATEWAY_TYPES_CONTAIN_NO_RAW_SECRET_FIELDS.check(productionClasses);
    }
}
