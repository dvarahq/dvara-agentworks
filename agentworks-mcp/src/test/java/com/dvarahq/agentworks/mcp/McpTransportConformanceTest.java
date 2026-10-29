package com.dvarahq.agentworks.mcp;

import com.dvarahq.agentworks.agent.springai.CredentialedToolCallback;
import com.dvarahq.agentworks.capability.*;
import com.dvarahq.agentworks.domain.security.PermissionRisk;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class McpTransportConformanceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DirectMcpClient.Identity IDENTITY = new DirectMcpClient.Identity("fixture", "fixture", "1.0.0",
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private static final String PYTHON_SERVER = """
            import json,sys
            for line in sys.stdin:
              r=json.loads(line); m=r.get('method'); i=r.get('id')
              if m=='notifications/initialized': continue
              if m=='initialize': x={'protocolVersion':'2025-06-18','capabilities':{'tools':{},'resources':{},'prompts':{}},'serverInfo':{'name':'stdio-fixture','version':'1'}}
              elif m=='tools/list': x={'tools':[{'name':'add','description':'Add one','inputSchema':{'type':'object','additionalProperties':False,'properties':{'value':{'type':'integer'}},'required':['value']}}]}
              elif m=='resources/list': x={'resources':[{'uri':'fixture://guide','name':'guide','description':'Guide','mimeType':'text/plain','size':5}]}
              elif m=='prompts/list': x={'prompts':[{'name':'summarize','description':'Summarize'}]}
              elif m=='tools/call': x={'content':[{'type':'text','text':str(r['params']['arguments']['value']+1)}],'isError':False}
              elif m=='resources/read': x={'contents':[{'uri':'fixture://guide','mimeType':'text/plain','text':'guide'}]}
              elif m=='prompts/get': x={'description':'summary','messages':[{'role':'user','content':{'type':'text','text':'summarize'}}]}
              elif m=='ping': x={}
              else: x={}
              print(json.dumps({'jsonrpc':'2.0','id':i,'result':x},separators=(',',':')),flush=True)
            """;

    @Test void officialSdkRunsThroughSandboxOwnedStdioTransport() {
        AtomicReference<DirectMcpClient.Identity> scoped = new AtomicReference<>();
        var factory = SdkMcpSessions.sandboxedStdio((identity, maxBytes) -> {
            scoped.set(identity);
            var parameters = ServerParameters.builder("python3").args("-u", "-c", PYTHON_SERVER).build();
            return new StdioClientTransport(parameters, McpJsonDefaults.getMapper(), maxBytes);
        }, Duration.ofSeconds(3), 64 * 1024);
        var client = new DirectMcpClient(IDENTITY, policy(Optional.empty()), allowAll(), factory, null, null);

        DirectMcpClient.Catalog catalog = client.discover(null);
        assertThat(scoped.get()).isEqualTo(IDENTITY);
        assertThat(catalog.tools()).hasSize(1); assertThat(catalog.resources()).hasSize(1); assertThat(catalog.prompts()).hasSize(1);
        assertThat(catalog.tools().getFirst().binding().callback().call("{\"value\":8}")).contains("9");
        assertThat(client.readResource("fixture://guide", null)).contains("guide");
        assertThat(client.getPrompt("summarize", Map.of(), null)).contains("summarize");
    }

    @Test void officialSdkRejectsAnUnknownProtocolBeforeDiscovery() {
        String futureProtocolServer = PYTHON_SERVER.replace("2025-06-18", "2099-01-01");
        var factory = SdkMcpSessions.sandboxedStdio((identity, maxBytes) -> {
            var parameters = ServerParameters.builder("python3").args("-u", "-c", futureProtocolServer).build();
            return new StdioClientTransport(parameters, McpJsonDefaults.getMapper(), maxBytes);
        }, Duration.ofSeconds(3), 64 * 1024);
        var client = new DirectMcpClient(IDENTITY, policy(Optional.empty()), allowAll(), factory, null, null);

        assertThatThrownBy(() -> client.discover(null)).isInstanceOf(RuntimeException.class);
    }

    @Test void streamableHttpUsesJitBearerRotationAndBoundedOfficialSdkProtocol() throws Exception {
        var authorizations = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> respond(exchange, authorizations));
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
            var factory = SdkMcpSessions.streamableHttp(endpoint, Duration.ofSeconds(1), Duration.ofSeconds(3),
                    64 * 1024, ignored -> true);
            var client = new DirectMcpClient(IDENTITY, policy(Optional.of("fixture.token")), allowAll(), factory, null, null);
            RegisteredMcpTool tool = client.discover("alpha".toCharArray()).tools().getFirst();
            ((CredentialedToolCallback) tool.binding().callback()).call("{\"value\":3}", null, "beta".toCharArray());
            assertThat(authorizations).contains("Bearer alpha", "Bearer beta");
            assertThat(client.toString()).doesNotContain("alpha", "beta");
        } finally { server.stop(0); }
    }

    @Test void productionHttpRegistrationRejectsLoopbackInsecureAndMutableUris() {
        for (URI uri : List.of(URI.create("http://example.com/mcp"), URI.create("https://user@example.com/mcp"),
                URI.create("https://example.com:8443/mcp"), URI.create("https://example.com/mcp?q=x"))) {
            assertThatIllegalArgumentException().isThrownBy(() -> SdkMcpSessions.streamableHttp(uri,
                    Duration.ofSeconds(1), Duration.ofSeconds(1), 4096));
        }
        for (String host : List.of("127.0.0.1", "192.0.2.1", "198.51.100.1", "203.0.113.1", "[2001:db8::1]")) {
            var factory = SdkMcpSessions.streamableHttp(URI.create("https://" + host + "/mcp"),
                    Duration.ofSeconds(1), Duration.ofSeconds(1), 4096);
            assertThatThrownBy(() -> factory.open(IDENTITY, null)).hasMessageContaining("MCP_DESTINATION_DENIED");
        }
    }

    private static void respond(HttpExchange exchange, List<String> authorizations) throws IOException {
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        if (!exchange.getRequestMethod().equals("POST")) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
        JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        String method = request.path("method").stringValue();
        if (method.equals("notifications/initialized")) { exchange.sendResponseHeaders(202, -1); exchange.close(); return; }
        Object result = switch (method) {
            case "initialize" -> Map.of("protocolVersion", "2025-06-18", "capabilities",
                    Map.of("tools", Map.of(), "resources", Map.of(), "prompts", Map.of()),
                    "serverInfo", Map.of("name", "http-fixture", "version", "1"));
            case "tools/list" -> Map.of("tools", List.of(Map.of("name", "add", "description", "Add one",
                    "inputSchema", Map.of("type", "object", "additionalProperties", false,
                            "properties", Map.of("value", Map.of("type", "integer")), "required", List.of("value")))));
            case "resources/list" -> Map.of("resources", List.of(Map.of("uri", "fixture://guide", "name", "guide",
                    "description", "Guide", "mimeType", "text/plain", "size", 5)));
            case "prompts/list" -> Map.of("prompts", List.of(Map.of("name", "summarize", "description", "Summarize")));
            case "tools/call" -> Map.of("content", List.of(Map.of("type", "text", "text", "4")), "isError", false);
            default -> Map.of();
        };
        byte[] body = JSON.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
    }

    private static DirectMcpClient.Policy policy(Optional<String> credential) {
        return new DirectMcpClient.Policy(Set.of("add"), Set.of("add"), Set.of("fixture://guide"), Set.of("fixture://guide"),
                Set.of("summarize"), Set.of("summarize"), Map.of("add", new ToolId("fixture", "add", "1.0.0")),
                Map.of("add", new ToolEffect("fixture", "read", PermissionRisk.LOW)), credential,
                Duration.ofSeconds(4), 16 * 1024, 64 * 1024, 2, DirectMcpClient.DriftPolicy.FAIL_CLOSED);
    }
    private static DirectMcpClient.AccessPolicy allowAll() { return (identity, action, kind, name) -> true; }
}
