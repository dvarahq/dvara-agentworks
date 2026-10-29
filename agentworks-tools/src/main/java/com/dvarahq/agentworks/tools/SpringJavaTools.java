package com.dvarahq.agentworks.tools;

import com.dvarahq.agentworks.capability.*;
import com.dvarahq.agentworks.agent.springai.ArtifactToolCallback;
import com.dvarahq.agentworks.agent.springai.ToolAdapterException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.util.ReflectionUtils;
import tools.jackson.databind.node.ObjectNode;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/** Explicit registration only; annotation scanning never exposes application beans automatically. */
public final class SpringJavaTools {
    private SpringJavaTools() {}

    public static RegisteredTool register(ToolId id, Object statelessBean, String methodToolName, ToolEffect effect) {
        return register(id, statelessBean, methodToolName, effect, false);
    }

    /** Set binaryResult when a scalar String itself encodes binary data. Non-scalar results always offload. */
    public static RegisteredTool register(ToolId id, Object statelessBean, String methodToolName, ToolEffect effect,
                                          boolean binaryResult) {
        if (statelessBean == null) throw new IllegalArgumentException("tool bean required");
        // Reject hidden mutable run/session state, including inherited and static mutable state.
        for (Class<?> type = statelessBean.getClass(); type != Object.class; type = type.getSuperclass()) {
            for (var field : type.getDeclaredFields()) {
                if (field.isSynthetic()) throw new IllegalArgumentException("capturing tool objects are not stateless");
                Class<?> fieldType = field.getType();
                if (!Modifier.isFinal(field.getModifiers()) || !(fieldType.isPrimitive()
                        || fieldType == String.class || fieldType.isEnum())) {
                    throw new IllegalArgumentException("tool fields must be immutable scalar configuration");
                }
            }
        }
        ToolCallback delegate = Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(statelessBean).build()
                .getToolCallbacks()).filter(callback -> callback.getToolDefinition().name().equals(methodToolName))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("explicit @Tool method not found"));
        var methods = Arrays.stream(ReflectionUtils.getAllDeclaredMethods(statelessBean.getClass()))
                .filter(method -> {
                    Tool annotation = method.getAnnotation(Tool.class);
                    return annotation != null && (annotation.name().isBlank() ? method.getName() : annotation.name()).equals(methodToolName);
                }).toList();
        if (methods.size() != 1) throw new IllegalArgumentException("an unambiguous @Tool method is required");
        Class<?> resultType = methods.getFirst().getReturnType();
        boolean scalar = resultType.isPrimitive() || resultType.isEnum() || resultType == String.class
                || resultType == Boolean.class || resultType == Character.class || resultType == Void.class
                || Number.class.isAssignableFrom(resultType);
        boolean offload = binaryResult || !scalar;
        var schema = (ObjectNode) AdapterSupport.input(delegate.getToolDefinition().inputSchema());
        schema.put("$comment", "Spring Java adapter 1; artifact=" + offload
                + "; returnDirect=" + delegate.getToolMetadata().returnDirect());
        ToolDefinition definition = ToolDefinition.builder().name(delegate.getToolDefinition().name())
                .description(delegate.getToolDefinition().description()).inputSchema(schema.toString()).build();
        ToolCallback safe = new ArtifactToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
            @Override public boolean requiresArtifact(String output) {
                return offload || ArtifactToolCallback.super.requiresArtifact(output);
            }
            @Override public String call(String input) { return call(input, null); }
            @Override public String call(String input, ToolContext context) {
                AdapterSupport.input(input);
                try { return AdapterSupport.bounded(delegate.call(input, context)); }
                catch (ToolAdapterException refusal) { throw refusal; }
                catch (RuntimeException failure) { throw AdapterSupport.failure("JAVA_TOOL_FAILED"); }
            }
        };
        return RegisteredTool.of(id, ToolType.SPRING, effect, safe, null);
    }
}
