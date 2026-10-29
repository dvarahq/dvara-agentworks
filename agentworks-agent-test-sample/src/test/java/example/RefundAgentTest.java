package example;

import com.dvarahq.agentworks.testing.AgentTest;
import com.dvarahq.agentworks.testing.AgentTestStatus;
import org.junit.jupiter.api.Test;

import static com.dvarahq.agentworks.testing.AgentTests.agent;

@AgentTest(value = "refund-agent", seed = 42)
class RefundAgentTest {
    @Test void answersWithoutStartingAConsoleOrServer() {
        agent().givenUser("refund order 123").run()
                .expectStatus(AgentTestStatus.SUCCEEDED)
                .expectAnswerContains("refund order 123")
                .expectNoHumanInteraction()
                .expectMaxToolCalls(0);
    }
}
