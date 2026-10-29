package com.dvarahq.agentworks.tools;

import com.dvarahq.agentworks.agent.springai.*;
import com.dvarahq.agentworks.capability.*;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Fixed-origin HTTP operations. TLS retains the original hostname; every actual connection uses checked DNS. */
public final class HttpTools {
    private static final Set<String> RESERVED = Set.of("authorization", "proxy-authorization", "host", "connection",
            "content-length", "transfer-encoding", "cookie", "content-type", "accept-encoding", "idempotency-key");
    private static final Pattern VARIABLE = Pattern.compile("\\{([a-z][a-zA-Z0-9_]{0,31})}");
    private HttpTools() {}

    /** Header values and path parameters come from bounded model input; origin, method and budgets are operator-owned. */
    public record Policy(URI origin, String pathTemplate, String method, Set<String> headers,
                         Duration timeout, int maxResponseBytes, int maxAttempts, Map<String, String> bodyPatterns) {
        public Policy(URI origin, String pathTemplate, String method, Set<String> headers,
                      Duration timeout, int maxResponseBytes, int maxAttempts) {
            this(origin, pathTemplate, method, headers, timeout, maxResponseBytes, maxAttempts, Map.of());
        }
        public Policy {
            Objects.requireNonNull(origin); Objects.requireNonNull(pathTemplate);
            if (!"https".equals(origin.getScheme()) || origin.getHost() == null || origin.getUserInfo() != null
                    || origin.getRawQuery() != null || origin.getFragment() != null
                    || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))
                    || origin.getPort() != -1 && origin.getPort() != 443) {
                throw new IllegalArgumentException("HTTP tools require an HTTPS origin on port 443");
            }
            if (!pathTemplate.startsWith("/") || pathTemplate.startsWith("//") || pathTemplate.contains("..")
                    || !VARIABLE.matcher(pathTemplate).replaceAll("x").matches("/[A-Za-z0-9/_-]*")) {
                throw new IllegalArgumentException("template must be an absolute path of literal segments and variables");
            }
            if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
                throw new IllegalArgumentException("HTTP method not supported");
            }
            headers = Set.copyOf(headers);
            for (String name : headers) if (!name.matches("[a-z][a-z0-9-]{0,63}") || RESERVED.contains(name)) {
                throw new IllegalArgumentException("header name is reserved or invalid");
            }
            timeout = AdapterSupport.timeout(timeout);
            bodyPatterns = Map.copyOf(bodyPatterns);
            if (bodyPatterns.size() > 32 || method.equals("GET") && !bodyPatterns.isEmpty()) {
                throw new IllegalArgumentException("GET cannot have a body; at most 32 body fields");
            }
            bodyPatterns.forEach((key, pattern) -> {
                if (!key.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) throw new IllegalArgumentException("invalid body field");
                AdapterSupport.safePattern(pattern);
            });
            if (maxResponseBytes < 1 || maxResponseBytes > 128 * 1024 || maxAttempts < 1 || maxAttempts > 3
                    || !method.equals("GET") && maxAttempts != 1) {
                throw new IllegalArgumentException("invalid HTTP bounds; only GET may retry");
            }
        }
    }

    public static RegisteredTool register(ToolId id, String name, Policy policy, ToolEffect effect, String credentialName) {
        var operation = new Operation(name, policy, InetAddress::getAllByName, PublicDestinations::allowed, null);
        ToolCallback callback = credentialName == null ? operation : new CredentialedArtifactCallback() {
            @Override public ToolDefinition getToolDefinition() { return operation.getToolDefinition(); }
            @Override public String call(String input, ToolContext context, char[] material) {
                return operation.invoke(input, context, material);
            }
            @Override public boolean requiresArtifact(String output) { return true; }
        };
        return RegisteredTool.of(id, ToolType.HTTP, effect, callback, credentialName);
    }

    @FunctionalInterface interface Resolver { InetAddress[] resolve(String host) throws UnknownHostException; }
    private interface CredentialedArtifactCallback extends CredentialedToolCallback, ArtifactToolCallback {}

    static final class Operation implements ArtifactToolCallback {
        private final Policy policy;
        private final Resolver resolver;
        private final Predicate<InetAddress> permitted;
        private final URI testOrigin;
        private final ToolDefinition definition;
        // Resolver calls may ignore interruption. Bound outstanding workers even when the system resolver stalls.
        private final Semaphore inFlight = new Semaphore(32);

        // Package-private seams allow a loopback fake server without exposing a production bypass.
        Operation(String name, Policy policy, Resolver resolver, Predicate<InetAddress> permitted, URI testOrigin) {
            this.policy = policy; this.resolver = resolver; this.permitted = permitted; this.testOrigin = testOrigin;
            var properties = AdapterSupport.JSON.createObjectNode();
            var parameters = properties.putObject("parameters").put("type", "object").put("additionalProperties", false);
            var parameterProperties = parameters.putObject("properties");
            var required = parameters.putArray("required");
            VARIABLE.matcher(policy.pathTemplate()).results().map(match -> match.group(1)).distinct().sorted().forEach(key -> {
                parameterProperties.putObject(key).put("type", "string").put("minLength", 1).put("maxLength", 128)
                        .put("$comment", "adapter validates ASCII letters, digits, underscore and hyphen");
                required.add(key);
            });
            var headers = properties.putObject("headers").put("type", "object").put("additionalProperties", false)
                    .putObject("properties");
            policy.headers().stream().sorted().forEach(key -> headers.putObject(key).put("type", "string")
                    .put("maxLength", 1024).put("$comment", "adapter validates printable ASCII"));
            if (!policy.method().equals("GET")) {
                var body = properties.putObject("body").put("type", "object").put("additionalProperties", false).putObject("properties");
                policy.bodyPatterns().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> body
                        .putObject(entry.getKey()).put("type", "string").put("maxLength", 4096)
                        .put("$comment", "adapter policy: " + entry.getValue()));
            }
            var schema = AdapterSupport.JSON.createObjectNode().put("$schema", "https://json-schema.org/draft/2020-12/schema")
                    .put("type", "object").put("additionalProperties", false)
                    .put("$comment", "HTTP adapter 1: " + policy.origin() + policy.pathTemplate() + " " + policy.method()
                            + " " + policy.timeout() + " " + policy.maxResponseBytes() + " " + policy.maxAttempts());
            schema.set("properties", properties); schema.putArray("required").add("parameters");
            definition = ToolDefinition.builder().name(name).description("Invoke the registered bounded HTTP operation")
                    .inputSchema(schema.toString()).build();
        }
        @Override public ToolDefinition getToolDefinition() { return definition; }
        @Override public boolean requiresArtifact(String output) { return true; }
        @Override public String call(String input) { return call(input, null); }
        @Override public String call(String input, ToolContext context) { return invoke(input, context, null); }

        String invoke(String input, ToolContext context, char[] material) {
            JsonNode request = AdapterSupport.input(input);
            String path = policy.pathTemplate();
            Set<String> expected = new HashSet<>();
            for (var match : VARIABLE.matcher(path).results().toList()) {
                String key = match.group(1); expected.add(key);
                String value = AdapterSupport.text(request.path("parameters"), key);
                if (!value.matches("[A-Za-z0-9_-]{1,128}")) throw AdapterSupport.failure("HTTP_PATH_DENIED");
                path = path.replace("{" + key + "}", value);
            }
            if (!request.path("parameters").isObject() || !request.path("parameters").propertyNames().equals(expected)
                    || !Set.of("parameters", "headers", "body").containsAll(request.propertyNames())) {
                throw AdapterSupport.failure("TOOL_INPUT_INVALID");
            }
            URI uri = (testOrigin == null ? policy.origin() : testOrigin).resolve(path);
            Map<String, String> headers = new LinkedHashMap<>();
            if (request.has("headers")) {
                if (!request.get("headers").isObject()) throw AdapterSupport.failure("HTTP_HEADER_DENIED");
                for (String key : request.get("headers").propertyNames()) {
                    String value = AdapterSupport.text(request.get("headers"), key);
                    if (!policy.headers().contains(key) || !value.matches("[ -~]{0,1024}")) {
                        throw AdapterSupport.failure("HTTP_HEADER_DENIED");
                    }
                    headers.put(key, value);
                }
            }
            if (request.has("body")) {
                JsonNode bodyInput = request.get("body");
                if (!bodyInput.isObject() || !policy.bodyPatterns().keySet().containsAll(bodyInput.propertyNames())) {
                    throw AdapterSupport.failure("HTTP_BODY_DENIED");
                }
                for (String key : bodyInput.propertyNames()) {
                    String value = AdapterSupport.text(bodyInput, key);
                    if (value.length() > 4096 || !value.matches(policy.bodyPatterns().get(key))) throw AdapterSupport.failure("HTTP_BODY_DENIED");
                }
            }
            String body = request.has("body") ? request.get("body").toString() : "{}";
            if (body.getBytes(StandardCharsets.UTF_8).length > 65536 || policy.method().equals("GET") && request.has("body")) {
                throw AdapterSupport.failure("HTTP_BODY_DENIED");
            }
            if (!policy.method().equals("GET")) headers.put("Idempotency-Key", ToolEffectContext.idempotencyKey(context)
                    .orElseThrow(() -> AdapterSupport.failure("HTTP_IDEMPOTENCY_REQUIRED")));
            String secret = material == null ? null : new String(material);
            if (secret != null) {
                if (!secret.matches("[!-~]{1,4096}")) throw AdapterSupport.failure("HTTP_CREDENTIAL_INVALID");
                headers.put("Authorization", "Bearer " + secret);
            }
            var cancellation = new Cancellation();
            if (!inFlight.tryAcquire()) throw AdapterSupport.failure("HTTP_CAPACITY_EXCEEDED");
            var task = new FutureTask<String>(() -> perform(uri, headers, body, secret, cancellation));
            Thread worker = Thread.ofVirtual().name("agentworks-http-tool").start(() -> {
                try { task.run(); } finally { inFlight.release(); }
            });
            try {
                return task.get(policy.timeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                cancellation.cancel(); task.cancel(true); worker.interrupt(); Thread.currentThread().interrupt();
                throw AdapterSupport.failure("TOOL_CANCELLED");
            } catch (TimeoutException timeout) {
                cancellation.cancel(); task.cancel(true); worker.interrupt(); throw AdapterSupport.failure("HTTP_TIMEOUT");
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof ToolAdapterException typed) throw typed;
                throw AdapterSupport.failure("HTTP_TRANSPORT_FAILED");
            }
        }

        private String perform(URI uri, Map<String, String> headers, String body, String secret, Cancellation cancellation) throws Exception {
            DnsResolver dns = new DnsResolver() {
                @Override public InetAddress[] resolve(String host) throws UnknownHostException {
                    if (!host.equalsIgnoreCase(uri.getHost())) throw new UnknownHostException("destination denied");
                    InetAddress[] addresses = resolver.resolve(host);
                    cancellation.check();
                    if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(address -> !permitted.test(address))) {
                        throw AdapterSupport.failure("HTTP_DESTINATION_DENIED");
                    }
                    return addresses;
                }
                @Override public String resolveCanonicalHostname(String host) { return host; }
            };
            // Also check numeric hosts: HttpClient may bypass DNS for address literals.
            dns.resolve(uri.getHost());
            Timeout timeout = Timeout.ofMilliseconds(policy.timeout().toMillis());
            try (var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(dns)
                    .setMaxConnTotal(1).setMaxConnPerRoute(1)
                    .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(timeout)
                            .setSocketTimeout(timeout).build()).build();
                 var client = HttpClients.custom().setConnectionManager(manager).disableAutomaticRetries()
                         .disableRedirectHandling().disableCookieManagement().disableContentCompression()
                         .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(timeout)
                                 .setConnectionRequestTimeout(timeout).setHardCancellationEnabled(true).build()).build()) {
                for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    var outbound = new HttpUriRequestBase(policy.method(), uri);
                    cancellation.current.set(outbound);
                    cancellation.check();
                    headers.forEach(outbound::setHeader);
                    if (!policy.method().equals("GET")) outbound.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
                    try {
                        Result result = client.execute(outbound, response -> {
                            int status = response.getCode();
                            if (status >= 300 && status < 400) throw AdapterSupport.failure("HTTP_REDIRECT_DENIED");
                            if (response.getFirstHeader("Content-Encoding") != null) throw AdapterSupport.failure("HTTP_ENCODING_DENIED");
                            byte[] bytes = response.getEntity() == null ? new byte[0]
                                    : response.getEntity().getContent().readNBytes(policy.maxResponseBytes() + 1);
                            if (bytes.length > policy.maxResponseBytes()) throw AdapterSupport.failure("TOOL_OUTPUT_LIMIT");
                            return new Result(status, bytes);
                        });
                        if ((result.status == 429 || result.status >= 500) && attempt < policy.maxAttempts()) {
                            Thread.sleep(100L * attempt); continue;
                        }
                        // Never return headers. Credential-bearing responses are rejected before persistence/offload.
                        String text = new String(result.bytes, StandardCharsets.UTF_8);
                        if (secret != null && containsCredential(text, secret)) {
                            throw AdapterSupport.failure("TOOL_SECRET_IN_OUTPUT");
                        }
                        return AdapterSupport.JSON.writeValueAsString(Map.of("status", result.status, "attempts", attempt,
                                "encoding", "base64", "body", Base64.getEncoder().encodeToString(result.bytes)));
                    } finally { outbound.cancel(); cancellation.current.compareAndSet(outbound, null); }
                }
            }
            throw AdapterSupport.failure("HTTP_TRANSPORT_FAILED");
        }
        private record Result(int status, byte[] bytes) {}
    }

    private static boolean containsCredential(String text, String secret) {
        if (text.contains(secret) || text.contains(URLEncoder.encode(secret, StandardCharsets.UTF_8))
                || text.contains(Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)))) return true;
        try { return jsonContainsCredential(AdapterSupport.JSON.readTree(text), secret); }
        catch (RuntimeException notJson) { return false; }
    }

    private static boolean jsonContainsCredential(JsonNode node, String secret) {
        if (node == null) return false;
        if (node.isString()) return node.stringValue().contains(secret);
        if (node.isObject() && node.propertyNames().stream().anyMatch(key -> key.contains(secret))) return true;
        if (node.isContainer()) {
            for (JsonNode child : node) if (jsonContainsCredential(child, secret)) return true;
        }
        return false;
    }

    private static final class Cancellation {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicReference<HttpUriRequestBase> current = new AtomicReference<>();
        void cancel() {
            cancelled.set(true);
            var request = current.get();
            if (request != null) request.cancel();
        }
        void check() {
            if (cancelled.get()) throw AdapterSupport.failure("TOOL_CANCELLED");
        }
    }
}
