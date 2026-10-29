package com.dvarahq.agentworks.api.web;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The published OpenAPI description as a test oracle (issue #35): it resolves operations, parameters, and responses,
 * and validates actual response bodies against the documented JSON Schemas (OpenAPI 3.1 schemas are JSON Schema
 * 2020-12). Response schemas forbid undocumented members, so a field added to a response without the description
 * fails validation.
 */
final class OpenApiContract {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final List<String> METHODS = List.of("get", "put", "post", "delete", "patch", "head", "options");

    private final JsonNode document;
    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private final Map<String, Schema> schemas = new ConcurrentHashMap<>();
    /** Operations whose documented response was matched: "METHOD /path status". */
    final Set<String> validated = ConcurrentHashMap.newKeySet();

    private OpenApiContract(JsonNode document) {
        this.document = document;
    }

    static OpenApiContract load() {
        try (InputStream in = OpenApiContract.class.getClassLoader().getResourceAsStream(OpenApiController.RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + OpenApiController.RESOURCE);
            return new OpenApiContract(YAMLMapper.builder().build().readTree(in));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    JsonNode document() {
        return document;
    }

    /** Every operation keyed "METHOD /path". */
    Map<String, JsonNode> operations() {
        Map<String, JsonNode> operations = new LinkedHashMap<>();
        document.path("paths").properties().forEach(path -> METHODS.forEach(method -> {
            JsonNode operation = path.getValue().path(method);
            if (!operation.isMissingNode()) operations.put(method.toUpperCase(Locale.ROOT) + " " + path.getKey(), operation);
        }));
        return operations;
    }

    /** The operation's parameters with references resolved. */
    List<JsonNode> parameters(JsonNode operation) {
        List<JsonNode> parameters = new ArrayList<>();
        operation.path("parameters").values().forEach(parameter -> parameters.add(resolve(parameter)));
        return parameters;
    }

    Set<String> parameterNames(JsonNode operation, String in) {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (JsonNode parameter : parameters(operation)) {
            if (parameter.path("in").asString().equals(in)) names.add(parameter.path("name").asString());
        }
        return names;
    }

    JsonNode resolve(JsonNode node) {
        JsonNode current = node;
        while (current.has("$ref")) current = pointer(current.path("$ref").asString());
        return current;
    }

    JsonNode pointer(String reference) {
        if (!reference.startsWith("#/")) throw new AssertionError("external reference " + reference);
        JsonNode target = document.at(reference.substring(1));
        if (target.isMissingNode()) throw new AssertionError("unresolved reference " + reference);
        return target;
    }

    /**
     * Validates one response of {@code method template} against the description and records it. A status without its
     * own entry must be covered by {@code default}; a body must match the schema of its media type.
     */
    void validate(String method, String template, int status, String contentType, String body) {
        String key = method + " " + template;
        JsonNode operation = document.path("paths").path(template).path(method.toLowerCase(Locale.ROOT));
        if (operation.isMissingNode()) throw new AssertionError("undocumented operation " + key);
        JsonNode responses = operation.path("responses");
        JsonNode response = responses.path(Integer.toString(status));
        if (response.isMissingNode()) {
            if (status < 400) throw new AssertionError(key + " answered undocumented status " + status);
            response = responses.path("default");
            if (response.isMissingNode()) throw new AssertionError(key + " answered undocumented status " + status);
        }
        response = resolve(response);
        boolean hasBody = body != null && !body.isBlank();
        if (!hasBody) {
            if (response.has("content") && status != 304) {
                throw new AssertionError(key + " " + status + " documents a body but none was sent");
            }
            validated.add(key + " " + status);
            return;
        }
        String mediaType = contentType == null ? "" : contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        JsonNode declaredContent = response.path("content");
        JsonNode content = declaredContent.path(mediaType);
        if (content.isMissingNode()) content = declaredContent.path("*/*");
        if (content.isMissingNode()) {
            throw new AssertionError(key + " " + status + " sent undocumented media type " + mediaType
                    + " documented: " + declaredContent.propertyNames());
        }
        JsonNode schema = content.path("schema");
        if (mediaType.equals("text/event-stream") || mediaType.equals("application/yaml")
                || "binary".equals(schema.path("format").asString(""))) {
            validated.add(key + " " + status);
            return;
        }
        validateJson(schema, JSON.readTree(body), key + " " + status);
        validated.add(key + " " + status);
    }

    /** Validates one JSON value against a documented schema, such as an SSE event's data. */
    void validateJson(JsonNode schema, JsonNode value, String context) {
        Schema compiled = schemas.computeIfAbsent(schema.toString(), ignored -> compile(schema));
        List<Error> errors = compiled.validate(value);
        if (!errors.isEmpty()) {
            throw new AssertionError(context + " does not match its documented schema: " + errors + "\nbody: " + value);
        }
    }

    JsonNode schema(String name) {
        JsonNode schema = document.path("components").path("schemas").path(name);
        if (schema.isMissingNode()) throw new AssertionError("undocumented schema " + name);
        return JSON.createObjectNode().put("$ref", "#/components/schemas/" + name);
    }

    private Schema compile(JsonNode schema) {
        ObjectNode wrapper = JSON.createObjectNode();
        wrapper.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        wrapper.put("$id", "urn:agentworks:openapi:" + Integer.toHexString(schema.toString().hashCode()));
        schema.properties().forEach(property -> wrapper.set(property.getKey(), property.getValue()));
        wrapper.set("components", document.path("components"));
        return registry.getSchema(SchemaLocation.of(wrapper.path("$id").asString()), wrapper);
    }
}
