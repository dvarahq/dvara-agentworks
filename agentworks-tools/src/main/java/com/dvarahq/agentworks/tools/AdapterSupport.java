package com.dvarahq.agentworks.tools;

import com.dvarahq.agentworks.agent.springai.ToolAdapterException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

final class AdapterSupport {
    static final JsonMapper JSON = JsonMapper.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static final int MAX_BYTES = 256 * 1024;
    private AdapterSupport() {}

    /** A bounded operator pattern: no groups, alternation, escapes, or multiple repetition operators. */
    static void safePattern(String expression) {
        if (expression == null || expression.isEmpty() || expression.length() > 256) {
            throw new IllegalArgumentException("policy patterns must be 1 to 256 characters");
        }
        boolean characterClass = false;
        int repetitions = 0;
        for (char c : expression.toCharArray()) {
            if (c == '\\') throw new IllegalArgumentException("policy pattern escapes are not supported; use character classes");
            if (characterClass) {
                if (c == '[') throw new IllegalArgumentException("nested character classes are not supported");
                if (c == ']') characterClass = false;
            } else if (c == '[') {
                characterClass = true;
            } else {
                if (c == '(' || c == ')' || c == '|') throw new IllegalArgumentException("policy pattern groups and alternatives are not supported");
                if ((c == '*' || c == '+' || c == '?' || c == '{') && ++repetitions > 1) {
                    throw new IllegalArgumentException("policy patterns permit at most one repetition");
                }
            }
        }
        Pattern.compile(expression);
    }

    static JsonNode input(String input) {
        if (input == null || input.length() > MAX_BYTES || input.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw failure("TOOL_INPUT_INVALID");
        }
        try {
            JsonNode value = JSON.readTree(input);
            if (!value.isObject()) throw failure("TOOL_INPUT_INVALID");
            return value;
        } catch (ToolAdapterException failure) { throw failure; }
        catch (RuntimeException invalid) { throw failure("TOOL_INPUT_INVALID"); }
    }

    static String text(JsonNode value, String name) {
        JsonNode field = value.get(name);
        if (field == null || !field.isString() || field.stringValue().indexOf('\0') >= 0) {
            throw failure("TOOL_INPUT_INVALID");
        }
        return field.stringValue();
    }

    static String bounded(String result) {
        if (result == null || result.length() > MAX_BYTES || result.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw failure("TOOL_OUTPUT_LIMIT");
        }
        return result;
    }

    static Duration timeout(Duration timeout) {
        if (timeout == null || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("tool timeout must be 1 ms to 2 minutes");
        }
        return timeout;
    }

    static ToolDefinition definition(String name, String description, String properties, String required) {
        return ToolDefinition.builder().name(name).description(description).inputSchema(
                "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\","
                + "\"additionalProperties\":false,\"properties\":{" + properties + "},\"required\":[" + required + "]}")
                .build();
    }
    static ToolAdapterException failure(String code) { return new ToolAdapterException(code); }
}
