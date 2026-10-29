package com.dvarahq.agentworks.api.web;

import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.web.AuthorizationMatrixTest;
import com.dvarahq.agentworks.identity.web.IdentityHttp;
import com.dvarahq.agentworks.identity.web.IdentityTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The published OpenAPI description is the contract (issue #35): it documents every endpoint, each parameter and
 * header the handlers read, the authorization every operation needs (checked against the independent authorization
 * matrix), the idempotency behavior of every mutation, and the error model; every reference resolves; and the
 * server publishes exactly this document.
 */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IdentityTestConfiguration.class)
class OpenApiContractTest {
    private static final Pattern VARIABLE_CONSTRAINT = Pattern.compile("\\{([A-Za-z]+):[^}]*}");
    private final OpenApiContract contract = OpenApiContract.load();

    @LocalServerPort
    int port;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void theServerPublishesExactlyThisDescriptionToAnyone() throws Exception {
        var response = new IdentityHttp(port).send("GET", OpenApiController.PATH, null, Map.of("Accept", "application/yaml"));
        byte[] expected;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(OpenApiController.RESOURCE)) {
            expected = in.readAllBytes();
        }

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("application/yaml");
        assertThat(response.header("ETag")).matches("\"[0-9a-f]{64}\"");
        assertThat(response.raw()).isEqualTo(new String(expected, StandardCharsets.UTF_8));
        assertThat(contract.document().path("openapi").asString()).isEqualTo("3.1.0");
        assertThat(contract.document().path("info").path("version").asString()).isEqualTo("1");
    }

    @Test
    void everyEndpointIsDocumentedAndEveryDocumentedOperationExists() {
        Set<String> served = new TreeSet<>(handlers().keySet());
        Set<String> documented = new TreeSet<>(contract.operations().keySet());

        assertThat(documented).isEqualTo(served);
    }

    @Test
    void everyParameterAndHeaderAHandlerReadsIsDocumentedAndNothingElse() {
        Map<String, String> mismatches = new TreeMap<>();
        handlers().forEach((key, handler) -> {
            JsonNode operation = contract.operations().get(key);
            Map<String, Set<String>> read = Map.of("path", new TreeSet<>(String.CASE_INSENSITIVE_ORDER),
                    "query", new TreeSet<>(String.CASE_INSENSITIVE_ORDER),
                    "header", new TreeSet<>(String.CASE_INSENSITIVE_ORDER));
            for (MethodParameter parameter : handler.getMethodParameters()) {
                PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
                RequestParam query = parameter.getParameterAnnotation(RequestParam.class);
                RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
                if (path != null) read.get("path").add(name(path.name(), path.value(), parameter));
                if (query != null) read.get("query").add(name(query.name(), query.value(), parameter));
                // The request body's media type is documented as the request body, not as a header.
                if (header != null && !name(header.name(), header.value(), parameter).equalsIgnoreCase("Content-Type")) {
                    read.get("header").add(name(header.name(), header.value(), parameter));
                }
            }
            for (String in : List.of("path", "query", "header")) {
                Set<String> documented = contract.parameterNames(operation, in);
                if (!documented.equals(read.get(in))) {
                    mismatches.put(key + " " + in, "documented " + documented + " but the handler reads " + read.get(in));
                }
            }
        });
        assertThat(mismatches).isEmpty();
    }

    @Test
    void everyOperationDocumentsTheAuthorizationTheServerEnforces() {
        Map<String, String> expected = AuthorizationMatrixTest.expectedAuthorization();
        Map<String, String> documented = new TreeMap<>();
        contract.operations().forEach((key, operation) -> {
            JsonNode authorization = operation.path("x-agentworks-authorization");
            documented.put(key, authorization.path("need").asString() + (authorization.has("permission")
                    ? ":" + authorization.path("permission").asString() : ""));
        });
        Map<String, String> normalized = new TreeMap<>();
        expected.forEach((key, need) -> normalized.put(normalize(key), need));

        assertThat(documented).isEqualTo(normalized);
        contract.operations().forEach((key, operation) -> {
            boolean publicOperation = "PUBLIC".equals(operation.path("x-agentworks-authorization").path("need").asString());
            if (publicOperation) {
                assertThat(operation.path("security").isArray() && operation.path("security").isEmpty()).as(key).isTrue();
            } else {
                assertThat(operation.has("security")).as(key + " inherits the credential requirement").isFalse();
                assertThat(operation.path("responses").has("401")).as(key + " documents 401").isTrue();
                if (!"AUTHENTICATED".equals(operation.path("x-agentworks-authorization").path("need").asString())) {
                    assertThat(operation.path("responses").has("403")).as(key + " documents 403").isTrue();
                }
            }
        });
        assertThat(contract.document().path("security").toString()).contains("bearerAuth", "sessionCookie");
    }

    @Test
    void everyMutationDocumentsItsIdempotencyBehaviorAndErrorModel() {
        Set<String> operationIds = new HashSet<>();
        contract.operations().forEach((key, operation) -> {
            assertThat(operationIds.add(operation.path("operationId").asString())).as(key + " operationId").isTrue();
            assertThat(operation.path("responses").has("default")).as(key + " documents the problem model").isTrue();
            assertThat(contract.resolve(operation.path("responses").path("default")).path("content")
                    .has("application/problem+json")).as(key).isTrue();
            boolean keyed = contract.parameterNames(operation, "header").contains("Idempotency-Key");
            if (key.startsWith("GET ")) {
                assertThat(operation.has("x-agentworks-idempotency")).as(key).isFalse();
                assertThat(keyed).as(key).isFalse();
                return;
            }
            String idempotency = operation.path("x-agentworks-idempotency").asString();
            assertThat(idempotency).as(key).isIn("key", "natural", "none");
            assertThat(keyed).as(key + " accepts Idempotency-Key exactly when it declares key idempotency")
                    .isEqualTo(idempotency.equals("key"));
            if (idempotency.equals("natural")) {
                assertThat(operation.path("x-agentworks-idempotency-description").asString()).as(key).isNotBlank();
            }
            // Every 4xx and 5xx answer is a problem document.
            operation.path("responses").properties().forEach(response -> {
                if (response.getKey().startsWith("4") || response.getKey().startsWith("5")) {
                    assertThat(contract.resolve(response.getValue()).path("content").has("application/problem+json"))
                            .as(key + " " + response.getKey()).isTrue();
                }
            });
        });
    }

    @Test
    void everyReferenceResolvesAndEverySchemaIsUsed() {
        Set<String> references = new TreeSet<>();
        collect(contract.document(), references);
        for (String reference : references) contract.pointer(reference);
        Set<String> schemas = new TreeSet<>(contract.document().path("components").path("schemas").propertyNames());
        Set<String> used = new TreeSet<>();
        references.stream().filter(reference -> reference.startsWith("#/components/schemas/"))
                .forEach(reference -> used.add(reference.substring("#/components/schemas/".length())));

        assertThat(used).containsAll(schemas);
    }

    @Test
    void theResponseValidatorIsNotVacuous() {
        String alias = "{\"name\":\"latest\",\"releaseId\":\"" + java.util.UUID.randomUUID()
                + "\",\"version\":1,\"updatedAt\":\"2026-09-26T10:00:00Z\"}";
        contract.validate("GET", "/api/v1/release-aliases/{name}", 200, "application/json", alias);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> contract.validate("GET",
                "/api/v1/release-aliases/{name}", 200, "application/json", alias.replace("}", ",\"secret\":\"x\"}")))
                .isInstanceOf(AssertionError.class).hasMessageContaining("documented schema");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> contract.validate("GET",
                "/api/v1/release-aliases/{name}", 200, "application/json", "{\"name\":\"latest\"}"))
                .isInstanceOf(AssertionError.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> contract.validate("GET",
                "/api/v1/release-aliases/{name}", 202, "application/json", alias)).isInstanceOf(AssertionError.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> contract.validate("GET",
                "/api/v1/release-aliases/{name}", 404, "application/problem+json", "{\"status\":404}"))
                .isInstanceOf(AssertionError.class);
    }

    /** Every mapped API operation keyed "METHOD /path", with Spring pattern constraints removed. */
    private Map<String, HandlerMethod> handlers() {
        Map<String, HandlerMethod> handlers = new LinkedHashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
            for (String pattern : entry.getKey().getPatternValues()) {
                // The contract is /api/v1; the Web Console's pages (#36) are not API operations.
                if (!pattern.startsWith("/api/")) continue;
                for (var method : entry.getKey().getMethodsCondition().getMethods()) {
                    handlers.put(normalize(method.name() + " " + pattern), entry.getValue());
                }
            }
        }
        return handlers;
    }

    private static String normalize(String key) {
        return VARIABLE_CONSTRAINT.matcher(key).replaceAll("{$1}");
    }

    private static String name(String name, String value, MethodParameter parameter) {
        if (!name.isEmpty()) return name;
        if (!value.isEmpty()) return value;
        return parameter.getParameterName();
    }

    private static void collect(JsonNode node, Set<String> references) {
        if (node.isObject()) {
            node.properties().forEach(property -> {
                if (property.getKey().equals("$ref")) references.add(property.getValue().asString());
                else collect(property.getValue(), references);
            });
        } else if (node.isArray()) {
            node.values().forEach(value -> collect(value, references));
        }
    }
}
