package com.dvarahq.agentworks.memory;

import com.dvarahq.agentworks.context.ContextClassification;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryContractTest {
    @Test
    void conversationScopeRequiresLogicalSessionAndQueriesAreBounded() {
        MemoryScope withoutSession = new MemoryScope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null);
        assertThatThrownBy(() -> new MemoryQuery(MemoryKind.CONVERSATION, withoutSession, 10, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("logical session");
        assertThatThrownBy(() -> new MemoryQuery(MemoryKind.WORKING, withoutSession, 201, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("between 1 and 200");
    }

    @Test
    void knowledgeQueriesAreTenantScopedBoundedAndCancellable() {
        assertThatThrownBy(() -> new KnowledgeQuery(UUID.randomUUID(), UUID.randomUUID(), "q", 101, 1,
                Duration.ofSeconds(1), ContextClassification.INTERNAL)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ((RetrievalCancellation) () -> true).throwIfCancelled())
                .isInstanceOfSatisfying(KnowledgeRetrievalException.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.code())
                                .isEqualTo("KNOWLEDGE_CANCELLED"));
    }
}
