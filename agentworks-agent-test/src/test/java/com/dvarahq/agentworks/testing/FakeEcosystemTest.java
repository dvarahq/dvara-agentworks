package com.dvarahq.agentworks.testing;

import com.dvarahq.agentworks.testing.fake.*;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class FakeEcosystemTest {
    @Test void clockToolNetworkMcpAndA2aAreDeterministicAndRecorded() {
        FakeClock clock = new FakeClock(Instant.EPOCH).advance(Duration.ofSeconds(5));
        FakeTool tool = new FakeTool("lookup", args -> "order-" + args.get("id"));
        FakeNetwork network = new FakeNetwork().route(URI.create("https://fake.invalid/order"), new FakeNetwork.Response(200, "ok"));
        FakeMcpServer mcp = new FakeMcpServer("2025-06-18").tool(tool);
        FakeA2aPeer a2a = new FakeA2aPeer("a2a/1", request -> request.input() + ":done");

        assertThat(clock.instant()).isEqualTo(Instant.EPOCH.plusSeconds(5));
        assertThat(network.get(URI.create("https://fake.invalid/order")).body()).isEqualTo("ok");
        assertThat(mcp.discover()).containsExactly("lookup");
        assertThat(mcp.invoke("lookup", Map.of("id", 7))).isEqualTo("order-7");
        assertThat(tool.calls()).containsExactly(Map.of("id", 7));
        assertThat(a2a.invoke(new FakeA2aPeer.Request("correlation-1", "work", 9))).isEqualTo("work:done");
        assertThatThrownBy(() -> a2a.invoke(new FakeA2aPeer.Request("correlation-1", "work", 9))).hasMessage("A2A_REPLAYED_CORRELATION");
        assertThat(a2a.fresh().invoke(new FakeA2aPeer.Request("correlation-1", "work", 9))).isEqualTo("work:done");
        assertThatThrownBy(() -> network.get(URI.create("https://live.invalid"))).hasMessageContaining("ROUTE_MISSING");
    }

    @Test void modelUsesTheFixedSeedAndRecordsRequests() {
        FakeModel model = new FakeModel("model/7", request -> new FakeModel.Reply(request.userMessage() + ":" + request.seed(), 1, 2));
        assertThat(model.invoke(new FakeModel.Request("hello", 41)).answer()).isEqualTo("hello:41");
        assertThat(model.requests()).containsExactly(new FakeModel.Request("hello", 41));
    }
}
