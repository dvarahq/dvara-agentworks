package com.dvarahq.agentworks.gateway.openaicompat;

import com.dvarahq.agentworks.gateway.AdapterInvocation;
import com.dvarahq.agentworks.gateway.FinishReason;
import com.dvarahq.agentworks.gateway.GatewayErrorCode;
import com.dvarahq.agentworks.gateway.GatewayException;
import com.dvarahq.agentworks.gateway.ModelMessage;
import com.dvarahq.agentworks.gateway.ModelRequest;
import com.dvarahq.agentworks.gateway.ModelUsage;
import com.dvarahq.agentworks.gateway.ResponseFormat;
import com.dvarahq.agentworks.gateway.ToolCall;
import com.dvarahq.agentworks.gateway.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Mapping between Core's neutral request/response types and the OpenAI Chat Completions wire format. Parse failures
 * never include the offending bytes, which could contain prompt or response content.
 */
final class ChatCompletionsProtocol {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private ChatCompletionsProtocol() {
    }

    /** The request body. Invalid tool or response schemas fail before any credential or network use. */
    static byte[] requestBody(AdapterInvocation invocation, OpenAiCompatibleGatewayConfig.OutputTokenField tokenField) {
        ModelRequest request = invocation.request();
        ObjectNode body = JSON.createObjectNode();
        body.put("model", invocation.providerModel());
        ArrayNode messages = body.putArray("messages");
        for (ModelMessage message : request.messages()) {
            messages.add(message(message));
        }
        body.put("stream", invocation.plan().stream());
        if (invocation.plan().stream() && invocation.plan().usage()) {
            body.putObject("stream_options").put("include_usage", true);
        }
        if (invocation.plan().tools() && !request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolDefinition tool : request.tools()) {
                ObjectNode function = tools.addObject().put("type", "function").putObject("function");
                function.put("name", tool.name()).put("description", tool.description());
                function.set("parameters", schema(tool.parametersJsonSchema(), "tool " + tool.name()));
            }
        }
        if (invocation.plan().structuredOutput() && request.responseFormat().kind() == ResponseFormat.Kind.JSON_SCHEMA) {
            ObjectNode format = body.putObject("response_format").put("type", "json_schema");
            ObjectNode schema = format.putObject("json_schema").put("name", request.responseFormat().name())
                    .put("strict", request.responseFormat().strict());
            schema.set("schema", schema(request.responseFormat().jsonSchema(), "response format"));
        }
        if (request.maxOutputTokens() != null) {
            body.put(tokenField.wireName(), request.maxOutputTokens());
        }
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        return JSON.writeValueAsBytes(body);
    }

    private static ObjectNode message(ModelMessage message) {
        ObjectNode node = JSON.createObjectNode();
        switch (message.role()) {
            case SYSTEM -> node.put("role", "system").put("content", message.content());
            case USER -> node.put("role", "user").put("content", message.content());
            case TOOL -> node.put("role", "tool").put("tool_call_id", message.toolCallId())
                    .put("content", message.content());
            case ASSISTANT -> {
                node.put("role", "assistant");
                if (message.content() == null) {
                    node.putNull("content");
                } else {
                    node.put("content", message.content());
                }
                if (!message.toolCalls().isEmpty()) {
                    ArrayNode calls = node.putArray("tool_calls");
                    for (ToolCall call : message.toolCalls()) {
                        calls.addObject().put("id", call.id()).put("type", "function").putObject("function")
                                .put("name", call.name()).put("arguments", call.argumentsJson());
                    }
                }
            }
        }
        return node;
    }

    private static JsonNode schema(String json, String owner) {
        try {
            JsonNode schema = JSON.readTree(json);
            if (schema == null || !schema.isObject()) {
                throw new GatewayException(GatewayErrorCode.INVALID_REQUEST, owner + " schema must be a JSON object");
            }
            return schema;
        } catch (JacksonException malformed) {
            throw new GatewayException(GatewayErrorCode.INVALID_REQUEST, owner + " schema is not valid JSON");
        }
    }

    /** One JSON read from the provider; a transport failure surfaces as the underlying {@link IOException}. */
    @FunctionalInterface
    interface JsonRead {
        JsonNode read() throws IOException;
    }

    /**
     * Parses one JSON object from the provider (a stream event or a whole body). Malformed JSON or UTF-8 is
     * {@code INVALID_PROVIDER_RESPONSE}, and the offending text never enters the message. An I/O failure underneath
     * (a byte bound, cancellation, a broken connection) is rethrown for the adapter to classify.
     */
    static JsonNode parse(JsonRead read, String what) throws IOException {
        JsonNode node;
        try {
            node = read.read();
        } catch (JacksonException malformed) {
            if (malformed.getCause() instanceof IOException transport) {
                throw transport;
            }
            throw invalid(what + " is not valid JSON");
        }
        if (node == null || !node.isObject()) {
            throw invalid(what + " is not a JSON object");
        }
        return node;
    }

    /** A provider-declared error object inside a successful HTTP response. */
    static void rejectErrorObject(JsonNode node) {
        if (node.has("error") && !node.get("error").isNull()) {
            throw new GatewayException(GatewayErrorCode.PROVIDER_REJECTED_REQUEST,
                    "provider returned an error object");
        }
    }

    static FinishReason finishReason(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        return switch (value.asString()) {
            case "stop" -> FinishReason.STOP;
            case "length" -> FinishReason.LENGTH;
            case "tool_calls", "function_call" -> FinishReason.TOOL_CALLS;
            case "content_filter" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.OTHER;
        };
    }

    /** A usage report: the normalized usage, or none, or a record that could not be normalized. */
    record ParsedUsage(ModelUsage usage, boolean malformed) {
        static final ParsedUsage NONE = new ParsedUsage(null, false);
    }

    /**
     * Normalized usage. A missing or incomplete record is {@link ParsedUsage#NONE}; a present but malformed one
     * (non-integer, negative, or overflowing counts) is reported as malformed, never as a failed completion, so Core
     * can degrade usage under the captured policy.
     */
    static ParsedUsage usage(JsonNode usage) {
        if (usage == null || usage.isNull()) {
            return ParsedUsage.NONE;
        }
        try {
            if (!usage.isObject()) {
                throw invalid("usage is not an object");
            }
            if (!count(usage.get("prompt_tokens")) || !count(usage.get("completion_tokens"))) {
                return ParsedUsage.NONE;
            }
            JsonNode total = usage.get("total_tokens");
            JsonNode cached = usage.path("prompt_tokens_details").get("cached_tokens");
            JsonNode reasoning = usage.path("completion_tokens_details").get("reasoning_tokens");
            return new ParsedUsage(ModelUsage.reported(usage.get("prompt_tokens").longValue(),
                    usage.get("completion_tokens").longValue(), count(total) ? total.longValue() : null,
                    count(cached) ? cached.longValue() : null, count(reasoning) ? reasoning.longValue() : null), false);
        } catch (GatewayException | ArithmeticException | IllegalArgumentException malformed) {
            return new ParsedUsage(null, true);
        }
    }

    private static boolean count(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0) {
            throw invalid("usage counts must be non-negative integers");
        }
        return true;
    }

    static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.isString() ? node.stringValue() : null;
    }

    /** A reported model or fingerprint: blank means unreported; an oversized value is an invalid response. */
    static String identity(JsonNode node) {
        String value = text(node);
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > 256) {
            throw invalid("reported model identity exceeds 256 characters");
        }
        return value;
    }

    /** Tool calls from a complete (non-streamed) assistant message. */
    static List<ToolCall> toolCalls(JsonNode calls, int maxCalls) {
        List<ToolCall> result = new ArrayList<>();
        if (calls == null || calls.isNull()) {
            return result;
        }
        if (!calls.isArray()) {
            throw invalid("tool_calls is not an array");
        }
        if (calls.size() > maxCalls) {
            throw tooManyToolCalls(maxCalls);
        }
        for (JsonNode call : calls.values()) {
            result.add(toolCall(text(call.get("id")), text(call.path("function").get("name")),
                    text(call.path("function").get("arguments"))));
        }
        return result;
    }

    static ToolCall toolCall(String id, String name, String arguments) {
        if (id == null || id.isBlank() || name == null || name.isBlank()) {
            throw invalid("tool call lacks an id or a name");
        }
        if (id.length() > 256 || name.length() > 256) {
            throw invalid("tool call id or name exceeds 256 characters");
        }
        return new ToolCall(id, name, arguments == null ? "" : arguments);
    }

    /** Accumulates streamed tool-call deltas by index. */
    static final class ToolCallAccumulator {
        private final Map<Integer, Partial> calls = new TreeMap<>();
        private final int maxCalls;

        ToolCallAccumulator(int maxCalls) {
            this.maxCalls = maxCalls;
        }

        private static final class Partial {
            String id;
            final StringBuilder name = new StringBuilder();
            final StringBuilder arguments = new StringBuilder();
        }

        /** Adds streamed deltas and returns the characters now retained for them (ids, names, and arguments). */
        long add(JsonNode deltas) {
            if (deltas == null || deltas.isNull()) {
                return 0;
            }
            long retained = 0;
            if (!deltas.isArray()) {
                throw invalid("streamed tool_calls is not an array");
            }
            for (JsonNode delta : deltas.values()) {
                JsonNode index = delta.get("index");
                if (index == null || !index.isInt() || index.intValue() < 0) {
                    throw invalid("streamed tool call has no index");
                }
                Partial call = calls.get(index.intValue());
                if (call == null) {
                    if (calls.size() >= maxCalls) {
                        throw tooManyToolCalls(maxCalls);
                    }
                    call = new Partial();
                    calls.put(index.intValue(), call);
                }
                String id = text(delta.get("id"));
                String name = text(delta.path("function").get("name"));
                String arguments = text(delta.path("function").get("arguments"));
                if (id != null) {
                    retained += id.length() - (call.id == null ? 0 : call.id.length());
                    call.id = id;
                }
                if (name != null) {
                    retained += name.length();
                    call.name.append(name);
                }
                if (arguments != null) {
                    retained += arguments.length();
                    call.arguments.append(arguments);
                }
            }
            return retained;
        }

        List<ToolCall> complete() {
            List<ToolCall> result = new ArrayList<>();
            for (Partial call : calls.values()) {
                result.add(toolCall(call.id, call.name.isEmpty() ? null : call.name.toString(),
                        call.arguments.toString()));
            }
            return result;
        }
    }

    static GatewayException tooManyToolCalls(int maxCalls) {
        return invalid("response carried more than " + maxCalls + " tool calls");
    }

    /**
     * The one strict UTF-8 reader for blocking bodies and streams: malformed or unmappable input fails instead of being
     * replaced, and a single leading byte-order mark (U+FEFF), which is valid UTF-8, is skipped.
     */
    static Reader strictUtf8(InputStream in) {
        Reader decoded = new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT));
        return new FilterReader(decoded) {
            private final char[] one = new char[1];
            private boolean started;

            @Override
            public int read() throws IOException {
                int count = read(one, 0, 1);
                return count < 0 ? -1 : one[0];
            }

            /** Skips through {@link #read(char[], int, int)}, so a leading byte-order mark is never counted (#174). */
            @Override
            public long skip(long characters) throws IOException {
                if (characters < 0) throw new IllegalArgumentException("skip value is negative");
                char[] scratch = new char[(int) Math.min(characters, 8192)];
                long skipped = 0;
                while (skipped < characters) {
                    int count = read(scratch, 0, (int) Math.min(scratch.length, characters - skipped));
                    if (count < 0) break;
                    skipped += count;
                }
                return skipped;
            }

            @Override
            public int read(char[] buffer, int offset, int length) throws IOException {
                int count = super.read(buffer, offset, length);
                if (!started && count > 0) {
                    started = true;
                    if (buffer[offset] == '\uFEFF') {
                        System.arraycopy(buffer, offset + 1, buffer, offset, count - 1);
                        count--;
                        if (count == 0) {
                            return read(buffer, offset, length);
                        }
                    }
                }
                return count;
            }
        };
    }

    static GatewayException invalid(String message) {
        return new GatewayException(GatewayErrorCode.INVALID_PROVIDER_RESPONSE, message);
    }
}
