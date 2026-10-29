package com.dvarahq.agentworks.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/** Pinned official Java SDK integration. No SDK or transport types cross the DirectMcpClient contract. */
public final class SdkMcpSessions {
    private static final int MAX_PAGES = 16;
    private SdkMcpSessions() { }

    /**
     * Fixed streamable-HTTP origin. Registration is trusted operator configuration; model input cannot change it.
     * DNS is checked on every session creation and operators must also enforce network egress policy against rebinding.
     */
    public static DirectMcpClient.SessionFactory streamableHttp(URI endpoint, Duration connectTimeout,
            Duration requestTimeout, int maxMessageBytes) {
        return streamableHttp(endpoint, connectTimeout, requestTimeout, maxMessageBytes, SdkMcpSessions::publicOnly, false);
    }

    static DirectMcpClient.SessionFactory streamableHttp(URI endpoint, Duration connectTimeout,
            Duration requestTimeout, int maxMessageBytes, DestinationPolicy destinations) {
        return streamableHttp(endpoint, connectTimeout, requestTimeout, maxMessageBytes, destinations, true);
    }

    private static DirectMcpClient.SessionFactory streamableHttp(URI endpoint, Duration connectTimeout,
            Duration requestTimeout, int maxMessageBytes, DestinationPolicy destinations, boolean testLoopback) {
        Objects.requireNonNull(endpoint, "endpoint"); Objects.requireNonNull(destinations, "destinations");
        boolean secure = "https".equalsIgnoreCase(endpoint.getScheme());
        if ((!secure && !(testLoopback && "http".equalsIgnoreCase(endpoint.getScheme()))) || endpoint.getUserInfo() != null
                || endpoint.getHost() == null || endpoint.getFragment() != null || endpoint.getQuery() != null
                || !testLoopback && endpoint.getPort() != -1 && endpoint.getPort() != 443) {
            throw new IllegalArgumentException("MCP HTTP endpoint must be a fixed HTTPS origin/path on port 443");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                || requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                || maxMessageBytes < 1024 || maxMessageBytes > 1024 * 1024) {
            throw new IllegalArgumentException("MCP HTTP bounds");
        }
        String path = endpoint.getRawPath();
        if (path == null || path.isBlank()) path = "/mcp";
        if (!path.startsWith("/") || path.contains("..") || path.contains("\\")) throw new IllegalArgumentException("MCP endpoint path");
        String authority = endpoint.getHost() + (endpoint.getPort() == -1 ? "" : ":" + endpoint.getPort());
        URI origin = URI.create(endpoint.getScheme() + "://" + authority);
        String fixedPath = path;
        return (identity, credential) -> {
            InetAddress[] addresses = InetAddress.getAllByName(endpoint.getHost());
            if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(address -> !destinations.allowed(address))) {
                throw new IllegalStateException("MCP_DESTINATION_DENIED");
            }
            String bearer = credential == null ? null : new String(credential);
            HttpClient.Builder http = HttpClient.newBuilder().connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .proxy(new NoProxySelector());
            var transport = HttpClientStreamableHttpTransport.builder(origin.toString())
                    .endpoint(fixedPath).clientBuilder(http).maxResponseSize(maxMessageBytes)
                    .resumableStreams(true).openConnectionOnStartup(false)
                    .httpRequestCustomizer((builder, method, uri, body, context) -> {
                        if (!uri.getScheme().equalsIgnoreCase(endpoint.getScheme()) || !uri.getHost().equalsIgnoreCase(endpoint.getHost())
                                || uri.getPort() != endpoint.getPort()) {
                            throw new IllegalStateException("MCP_DESTINATION_DENIED");
                        }
                        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
                    }).build();
            return sdk(transport, requestTimeout);
        };
    }

    /**
     * Sandboxed stdio requires a transport provider that owns process creation inside the exact pinned sandbox.
     * This module never turns a configured command into a host ProcessBuilder.
     */
    public static DirectMcpClient.SessionFactory sandboxedStdio(SandboxTransportProvider provider,
            Duration requestTimeout, int maxMessageBytes) {
        Objects.requireNonNull(provider, "provider");
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                || maxMessageBytes < 1024 || maxMessageBytes > 1024 * 1024) throw new IllegalArgumentException("stdio bounds");
        return (identity, credential) -> sdk(provider.open(identity, maxMessageBytes), requestTimeout);
    }

    private static DirectMcpClient.Session sdk(McpClientTransport transport, Duration timeout) {
        McpSyncClient client = McpClient.sync(transport).requestTimeout(timeout).initializationTimeout(timeout)
                .clientInfo(McpSchema.Implementation.builder("Dvara AgentWorks", "1").build()).build();
        return new SdkSession(client, McpJsonDefaults.getMapper());
    }

    private static final class SdkSession implements DirectMcpClient.Session {
        private final McpSyncClient client;
        private final McpJsonMapper json;
        private SdkSession(McpSyncClient client, McpJsonMapper json) { this.client = client; this.json = json; }
        @Override public void initialize() { client.initialize(); }

        @Override public DirectMcpClient.Discovery discover() {
            var capabilities = client.getServerCapabilities();
            List<DirectMcpClient.RemoteTool> tools = capabilities.tools() == null ? List.of() : tools();
            List<DirectMcpClient.Resource> resources = capabilities.resources() == null ? List.of() : resources();
            List<DirectMcpClient.Prompt> prompts = capabilities.prompts() == null ? List.of() : prompts();
            return new DirectMcpClient.Discovery(tools, resources, prompts);
        }

        private List<DirectMcpClient.RemoteTool> tools() {
            List<DirectMcpClient.RemoteTool> all = new ArrayList<>(); String cursor = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                McpSchema.ListToolsResult result = cursor == null ? client.listTools() : client.listTools(cursor);
                for (McpSchema.Tool tool : result.tools()) all.add(new DirectMcpClient.RemoteTool(
                        tool.name(), tool.description(), tool.inputSchema()));
                cursor = result.nextCursor(); if (cursor == null) return List.copyOf(all);
            }
            throw new IllegalStateException("MCP_DISCOVERY_LIMIT");
        }

        private List<DirectMcpClient.Resource> resources() {
            List<DirectMcpClient.Resource> all = new ArrayList<>(); String cursor = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                McpSchema.ListResourcesResult result = cursor == null ? client.listResources() : client.listResources(cursor);
                for (McpSchema.Resource resource : result.resources()) all.add(new DirectMcpClient.Resource(resource.uri(),
                        resource.name(), resource.description(), resource.mimeType(), resource.size()));
                cursor = result.nextCursor(); if (cursor == null) return List.copyOf(all);
            }
            throw new IllegalStateException("MCP_DISCOVERY_LIMIT");
        }

        private List<DirectMcpClient.Prompt> prompts() {
            List<DirectMcpClient.Prompt> all = new ArrayList<>(); String cursor = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                McpSchema.ListPromptsResult result = cursor == null ? client.listPrompts() : client.listPrompts(cursor);
                for (McpSchema.Prompt prompt : result.prompts()) all.add(new DirectMcpClient.Prompt(prompt.name(), prompt.description()));
                cursor = result.nextCursor(); if (cursor == null) return List.copyOf(all);
            }
            throw new IllegalStateException("MCP_DISCOVERY_LIMIT");
        }

        @Override public String callTool(String name, Map<String, Object> arguments) {
            return encode(client.callTool(new McpSchema.CallToolRequest(name, arguments, null)));
        }
        @Override public String readResource(String uri) {
            return encode(client.readResource(new McpSchema.ReadResourceRequest(uri, null)));
        }
        @Override public String getPrompt(String name, Map<String, Object> arguments) {
            return encode(client.getPrompt(new McpSchema.GetPromptRequest(name, arguments, null)));
        }
        private String encode(Object value) {
            try { return json.writeValueAsString(value); }
            catch (IOException failure) { throw new IllegalStateException("MCP_RESPONSE_INVALID"); }
        }
        @Override public void close() { client.close(); }
    }

    @FunctionalInterface public interface SandboxTransportProvider {
        McpClientTransport open(DirectMcpClient.Identity identity, int maxMessageBytes) throws Exception;
    }
    @FunctionalInterface interface DestinationPolicy { boolean allowed(InetAddress address); }

    private static boolean publicOnly(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 255, second = bytes[1] & 255, third = bytes[2] & 255;
            return !(first == 0 || first == 10 || first == 127 || first >= 224
                    || first == 100 && second >= 64 && second <= 127
                    || first == 169 && second == 254 || first == 172 && second >= 16 && second <= 31
                    || first == 192 && (second == 168 || second == 0 || second == 2 || second == 88)
                    || first == 198 && (second == 18 || second == 19 || second == 51 && third == 100)
                    || first == 203 && second == 0 && third == 113);
        }
        // Only ordinary 2000::/3 global unicast; deny Teredo, documentation, 6to4, NAT64 and ULA.
        return bytes.length == 16 && (bytes[0] & 0xe0) == 0x20
                && !((bytes[0] & 255) == 0x3f && (bytes[1] & 255) == 0xff)
                && !((bytes[0] & 255) == 0x20 && (bytes[1] & 255) == 0x02)
                && !((bytes[0] & 255) == 0x20 && (bytes[1] & 255) == 0x01 && (bytes[2] & 255) < 2)
                && !((bytes[0] & 255) == 0x20 && (bytes[1] & 255) == 0x01
                     && (bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8);
    }

    private static final class NoProxySelector extends ProxySelector {
        @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
        @Override public void connectFailed(URI uri, SocketAddress sa, IOException ioe) { }
    }
}
