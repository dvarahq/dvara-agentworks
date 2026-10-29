package com.dvarahq.agentworks.child;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChildRunContractsTest {
    @Test
    void requestCopiesLeastContextAndToolAuthorityAndRejectsUnboundedShapes() {
        Map<String, Object> context = new HashMap<>(Map.of("case", "42"));
        Set<String> tools = new HashSet<>(Set.of("lookup"));
        var request = new ChildRunRequest(UUID.randomUUID(), UUID.randomUUID(), "research", context, tools,
                new ChildRunBudget(10, 1, 1, 1000, new BigDecimal("0.100000")),
                ChildFailurePolicy.RETURN_OUTCOME, "call-1");
        context.put("secret", "must-not-appear"); tools.add("admin");
        assertThat(request.context()).containsOnlyKeys("case");
        assertThat(request.tools()).containsExactly("lookup");
        assertThatThrownBy(() -> new ChildRunBudget(-1, 1, 1, 1, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
