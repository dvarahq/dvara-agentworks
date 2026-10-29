package com.dvarahq.agentworks.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdkAgentWorksTransportTest {
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void resolvesAndZeroizesTokenPerRequestWithoutLeakingFailureDetail() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v1/runs/run-1", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"code\":\"RUN_REVOKED\",\"detail\":\"secret-canary\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(409, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        char[] token = "sdk-token".toCharArray();
        URI uri = URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort());
        try (AgentWorksClient client = AgentWorksClient.builder(uri).accessTokenProvider(() -> token).build()) {
            assertThatThrownBy(() -> client.execute(ApiCall.operation(ApiOperation.GET_RUN).path("id", "run-1").build()))
                    .isInstanceOf(ApiProblemException.class).hasMessageContaining("RUN_REVOKED")
                    .hasMessageNotContaining("secret-canary");
        }
        assertThat(authorization).hasValue("Bearer sdk-token");
        assertThat(token).containsOnly('\0');
    }
}
