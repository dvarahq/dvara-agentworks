package com.dvarahq.agentworks.architecture;

import com.dvarahq.agentworks.agent.AgentExecutor;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Locale;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

final class DvaraArchitectureRules {

    static final ArchRule DOMAIN_IS_FRAMEWORK_AND_PROVIDER_NEUTRAL = noClasses()
            .that().resideInAPackage("com.dvarahq.agentworks.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.ai..",
                    "com.openai..",
                    "com.anthropic..",
                    "software.amazon.awssdk.services.bedrockruntime..",
                    "com.azure.ai.openai..",
                    "com.google.cloud.vertexai..",
                    "com.google.genai..")
            .because("Dvara AgentWorks domain code is framework- and model-provider-neutral");

    static final ArchRule AGENT_EXECUTORS_ARE_STATELESS = classes()
            .that(implementAgentExecutor())
            .should(haveOnlyImmutableCollaboratorOrConfigurationFields())
            .because("AgentExecutor implementations externalize mutable run and session state")
            .allowEmptyShould(true);

    static final ArchRule RUN_PLANE_HAS_NO_CONTROL_MUTATION_AUTHORITY = noClasses()
            .that().resideInAPackage("com.dvarahq.agentworks.runplane..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.dvarahq.agentworks.controlplane.mutation..")
            .because("only the Control Plane may mutate intended or approved state");

    static final ArchRule RUN_PLANE_HAS_NO_LIFECYCLE_WRITE_ACCESS = noClasses()
            .that().resideInAPackage("com.dvarahq.agentworks.runplane..")
            .should().dependOnClassesThat().haveFullyQualifiedName(
                    "com.dvarahq.agentworks.storage.lifecycle.JdbcLifecycleRepository")
            .orShould().dependOnClassesThat().haveFullyQualifiedName(
                    "com.dvarahq.agentworks.storage.lifecycle.JdbcIdempotencyRepository")
            .because("the Run Plane reads approved lifecycle state through LifecycleReads and cannot author it");

    static final ArchRule CORE_HAS_NO_ENTERPRISE_DEPENDENCY = noDependencyOnPackage(
            "com.dvarahq.agentworks.enterprise..",
            "published Core must build independently of Enterprise implementation");

    static ArchRule noDependencyOnPackage(String forbiddenPackage, String reason) {
        return noClasses()
            .that().resideInAPackage("com.dvarahq.agentworks..")
            .and().resideOutsideOfPackage(forbiddenPackage)
            .should().dependOnClassesThat().resideInAPackage(forbiddenPackage)
            .because(reason);
    }

    static final ArchRule COMPILED_AGENT_STRUCTURES_CONTAIN_NO_RAW_SECRET_FIELDS = classes()
            .that().resideInAPackage("com.dvarahq.agentworks.domain.agent.compiled..")
            .should(notDeclareRawSecretFields())
            .because("compiled or approved agents retain CredentialRef handles, never secret material");

    private static final String[] PROVIDER_SDK_PACKAGES = {
            "com.openai..", "com.anthropic..", "software.amazon.awssdk..", "com.azure..", "com.google.cloud..",
            "com.google.genai..", "dev.langchain4j.."};

    /** ADR-009: the gateway SPI and fake carry no provider SDK, framework, reactive, transport, or adapter type. */
    static final ArchRule GATEWAY_SPI_IS_PROVIDER_AND_FRAMEWORK_NEUTRAL = noClasses()
            .that().resideInAnyPackage("com.dvarahq.agentworks.gateway", "com.dvarahq.agentworks.gateway.fake")
            .should().dependOnClassesThat().resideInAnyPackage(concat(PROVIDER_SDK_PACKAGES,
                    "org.springframework..", "reactor..", "java.net.http..",
                    "com.dvarahq.agentworks.gateway.openaicompat..", "com.dvarahq.agentworks.gateway.dvarahq.."))
            .because("Core gateway contracts compile without provider, DvaraHQ, Spring, or transport types (ADR-009)");

    static final ArchRule GATEWAY_ADAPTERS_USE_NO_PROVIDER_SDK = noClasses()
            .that().resideInAPackage("com.dvarahq.agentworks.gateway..")
            .should().dependOnClassesThat().resideInAnyPackage(PROVIDER_SDK_PACKAGES)
            .because("adapters speak provider protocols directly; provider SDKs are not Core dependencies");

    /** ADR-009 and ADR-023: DvaraHQ is optional and one-way; nothing outside its adapter package may reference it. */
    static final ArchRule CORE_DOES_NOT_DEPEND_ON_THE_DVARAHQ_ADAPTER = noClasses()
            .that().resideInAPackage("com.dvarahq.agentworks..")
            .and().resideOutsideOfPackage("com.dvarahq.agentworks.gateway.dvarahq..")
            .should().dependOnClassesThat().resideInAPackage("com.dvarahq.agentworks.gateway.dvarahq..")
            .because("Dvara AgentWorks must build and run without the optional DvaraHQ adapter");

    static final ArchRule ONLY_WIRING_SELECTS_CONCRETE_GATEWAY_ADAPTERS = noClasses()
            .that().resideInAPackage("com.dvarahq.agentworks..")
            .and().resideOutsideOfPackages("com.dvarahq.agentworks.gateway.openaicompat..",
                    "com.dvarahq.agentworks.gateway.fake..", "com.dvarahq.agentworks.autoconfigure..")
            .should().dependOnClassesThat().resideInAnyPackage("com.dvarahq.agentworks.gateway.openaicompat..",
                    "com.dvarahq.agentworks.gateway.fake..")
            .because("control-plane and run-plane code reach models only through the gateway SPI and release pins");

    static final ArchRule GATEWAY_TYPES_CONTAIN_NO_RAW_SECRET_FIELDS = classes()
            .that().resideInAPackage("com.dvarahq.agentworks.gateway..")
            .should(notDeclareRawSecretFields())
            .because("gateway configuration and pins hold CredentialRef handles; material is resolved just in time");

    private static String[] concat(String[] first, String... second) {
        String[] result = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static final Set<String> EXACT_SECRET_NAMES = Set.of(
            "password", "passwd", "pwd", "apikey", "token", "accesstoken",
            "refreshtoken", "secret", "clientsecret", "authtoken", "bearertoken",
            "credentialvalue", "authorization");

    private DvaraArchitectureRules() {
    }

    private static DescribedPredicate<JavaClass> implementAgentExecutor() {
        return new DescribedPredicate<>("implement AgentExecutor") {
            @Override
            public boolean test(JavaClass javaClass) {
                return !javaClass.isInterface() && javaClass.isAssignableTo(AgentExecutor.class);
            }
        };
    }

    private static ArchCondition<JavaClass> haveOnlyImmutableCollaboratorOrConfigurationFields() {
        return new ArchCondition<>("have only immutable collaborator/configuration fields") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaField field : javaClass.getAllFields()) {
                    if (field.getModifiers().contains(JavaModifier.STATIC)) {
                        continue;
                    }
                    if (!field.getModifiers().contains(JavaModifier.FINAL)) {
                        events.add(SimpleConditionEvent.violated(field,
                                field.getFullName() + " is a mutable instance field"));
                    } else if (isMutableStateContainer(field.getRawType())) {
                        events.add(SimpleConditionEvent.violated(field,
                                field.getFullName() + " uses a mutable state container"));
                    }
                }
            }
        };
    }

    private static boolean isMutableStateContainer(JavaClass type) {
        String name = type.getName();
        return type.isArray()
                || name.equals("java.lang.ThreadLocal")
                || name.startsWith("java.util.concurrent.atomic.")
                || type.isAssignableTo(java.util.Collection.class)
                || type.isAssignableTo(java.util.Map.class);
    }

    private static ArchCondition<JavaClass> notDeclareRawSecretFields() {
        return new ArchCondition<>("not declare raw secret fields") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaField field : javaClass.getAllFields()) {
                    String normalizedName = field.getName()
                            .replaceAll("[^A-Za-z0-9]", "")
                            .toLowerCase(Locale.ROOT);
                    if (looksLikeCredentialReference(normalizedName)
                            && !field.getType().getName().contains(CredentialRef.class.getName())) {
                        events.add(SimpleConditionEvent.violated(field,
                                field.getFullName() + " is named as a credential reference but is not typed as CredentialRef"));
                    } else if (looksLikeRawSecret(normalizedName)) {
                        events.add(SimpleConditionEvent.violated(field,
                                field.getFullName() + " looks like embedded raw credential material"));
                    }
                }
            }
        };
    }

    private static boolean looksLikeRawSecret(String fieldName) {
        if (looksLikeCredentialReference(fieldName)) {
            return false;
        }
        return EXACT_SECRET_NAMES.contains(fieldName)
                || fieldName.endsWith("password")
                || fieldName.endsWith("apikey")
                || fieldName.endsWith("accesstoken")
                || fieldName.endsWith("refreshtoken")
                || fieldName.endsWith("clientsecret")
                || fieldName.endsWith("authtoken")
                || fieldName.endsWith("bearertoken")
                || fieldName.endsWith("credentialvalue");
    }

    private static boolean looksLikeCredentialReference(String fieldName) {
        boolean referenceSuffix = fieldName.endsWith("ref") || fieldName.endsWith("refs")
                || fieldName.endsWith("reference") || fieldName.endsWith("references");
        return referenceSuffix && (fieldName.contains("credential")
                || fieldName.contains("password")
                || fieldName.contains("apikey")
                || fieldName.contains("token")
                || fieldName.contains("secret")
                || fieldName.contains("authorization"));
    }
}
