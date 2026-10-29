package com.dvarahq.agentworks.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class SpringAiAuthorityBoundaryTest {

    private static final ArchRule CHAT_MEMORY_IS_NOT_AUTHORITATIVE = noClasses().that().resideInAnyPackage(
                    "com.dvarahq.agentworks.domain..", "com.dvarahq.agentworks.runplane..",
                    "com.dvarahq.spikefixture.authoritative..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.ai.chat.memory..", "reactor..")
            .because("ChatMemory is adapter convenience, never authoritative RunState, SessionInbox, or checkpoint state");

    /**
     * Issue #34 AC6: the executors themselves never use Spring AI chat memory or its memory advisors, so no
     * conversation state can come from, or be kept in, anything but the run's durable input, events, and checkpoints.
     */
    private static final ArchRule EXECUTORS_USE_NO_CHAT_MEMORY = noClasses().that().resideInAnyPackage(
                    "com.dvarahq.agentworks.agent..", "com.dvarahq.spikefixture.authoritative..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai.chat.memory..")
            .orShould().dependOnClassesThat().haveFullyQualifiedName(
                    "org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor")
            .orShould().dependOnClassesThat().haveFullyQualifiedName(
                    "org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor")
            .because("ChatMemory can never satisfy RunState, inbox, event, or checkpoint authority (ADR-004)");

    /** Issue #34: Spring AI types terminate inside the executor adapter; the Run Plane never sees them. */
    private static final ArchRule RUN_PLANE_IS_SPRING_AI_FREE = noClasses().that()
            .resideInAnyPackage("com.dvarahq.agentworks.runplane..", "com.dvarahq.spikefixture.authoritative..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai..")
            .because("Spring AI and Reactor types terminate inside the executor adapter (issue #34)");

    /** Issue #49: durable memory is context input, never RunState, inbox, event, effect, or checkpoint authority. */
    private static final ArchRule MEMORY_RECORDS_ARE_NOT_RUN_AUTHORITY = noClasses().that().resideInAnyPackage(
                    "com.dvarahq.agentworks.domain.run..", "com.dvarahq.agentworks.session..",
                    "com.dvarahq.agentworks.runplane..", "com.dvarahq.spikefixture.memoryauthority..")
            .should().dependOnClassesThat().haveFullyQualifiedName("com.dvarahq.agentworks.memory.MemoryRecord")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.dvarahq.agentworks.memory.AgentMemoryStore")
            .because("memory records are compiler inputs, never RunState, inbox, event, effect, or checkpoint authority");

    @Test
    void authoritativeDomainAndRunPlaneCannotDependOnSpringAiMemoryOrReactor() {
        var classes = new ClassFileImporter().importPackages("com.dvarahq.agentworks");

        CHAT_MEMORY_IS_NOT_AUTHORITATIVE.check(classes);
    }

    @Test
    void executorsUseNoChatMemoryAndTheRunPlaneNoSpringAi() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.dvarahq.agentworks");

        EXECUTORS_USE_NO_CHAT_MEMORY.check(classes);
        RUN_PLANE_IS_SPRING_AI_FREE.check(classes);
        MEMORY_RECORDS_ARE_NOT_RUN_AUTHORITY.check(classes);
    }

    @Test
    void chatMemoryAuthorityRulesDetectDeliberateViolation() {
        var fixture = new ClassFileImporter().importPackages("com.dvarahq.spikefixture.authoritative");

        assertThat(CHAT_MEMORY_IS_NOT_AUTHORITATIVE.evaluate(fixture).hasViolation()).isTrue();
        assertThat(EXECUTORS_USE_NO_CHAT_MEMORY.evaluate(fixture).hasViolation()).isTrue();
        assertThat(RUN_PLANE_IS_SPRING_AI_FREE.evaluate(fixture).hasViolation()).isTrue();
        var memoryFixture = new ClassFileImporter().importPackages("com.dvarahq.spikefixture.memoryauthority");
        assertThat(MEMORY_RECORDS_ARE_NOT_RUN_AUTHORITY.evaluate(memoryFixture).hasViolation()).isTrue();
    }
}
