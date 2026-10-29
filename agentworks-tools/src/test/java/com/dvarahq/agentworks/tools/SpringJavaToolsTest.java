package com.dvarahq.agentworks.tools;

import com.dvarahq.agentworks.capability.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import static org.assertj.core.api.Assertions.*;

class SpringJavaToolsTest {
    @Test void binaryAndStructuredResultsRequireArtifactsEvenWhenSmall() {
        for (String method : java.util.List.of("binary", "structured")) {
            var callback = SpringJavaTools.register(ID, new Stateless(), method, ToolEffect.unclassified()).binding().callback();
            assertThat(((com.dvarahq.agentworks.agent.springai.ArtifactToolCallback) callback).requiresArtifact(callback.call("{}"))).isTrue();
        }
        var inline = SpringJavaTools.register(ID, new Stateless(), "echo", ToolEffect.unclassified());
        var encoded = SpringJavaTools.register(ID, new Stateless(), "echo", ToolEffect.unclassified(), true);
        assertThat(inline.metadata().schemaHash()).isNotEqualTo(encoded.metadata().schemaHash());
        assertThat(((com.dvarahq.agentworks.agent.springai.ArtifactToolCallback) encoded.binding().callback()).requiresArtifact("AAH/")).isTrue();
    }
    static final ToolId ID = new ToolId("test", "java", "1.0.0");
    @Test void explicitlySelectsAnnotationAndKeepsStableRegistrySchema() {
        var registered = SpringJavaTools.register(ID, new Stateless(), "echo", ToolEffect.unclassified());
        assertThat(registered.binding().callback().call("{\"value\":\"hello\"}")).contains("hello");
        assertThat(registered.metadata().schemaHash()).isEqualTo(AgentTool.digest(registered.binding().callback().getToolDefinition().inputSchema()));
        var direct = SpringJavaTools.register(ID, new Direct(), "echo", ToolEffect.unclassified());
        assertThat(direct.binding().callback().getToolMetadata().returnDirect()).isTrue();
        assertThat(registered.binding().callback().getToolMetadata().returnDirect()).isFalse();
        assertThat(direct.metadata().schemaHash()).isNotEqualTo(registered.metadata().schemaHash());
        assertThat(direct.binding().callback().call("{\"value\":\"hello\"}")).contains("hello");
        assertThatIllegalArgumentException().isThrownBy(() -> SpringJavaTools.register(ID, new Stateless(), "missing", ToolEffect.unclassified()));
    }
    @Test void mutableAndInheritedStateAreRejectedBeforeRegistration() {
        assertThatIllegalArgumentException().isThrownBy(() -> SpringJavaTools.register(ID, new Mutable(), "echo", ToolEffect.unclassified()));
        assertThatIllegalArgumentException().isThrownBy(() -> SpringJavaTools.register(ID, new Inherited(), "echo", ToolEffect.unclassified()));
    }
    @Test void exceptionMessagesCannotLeakApplicationSecrets() {
        var callback = SpringJavaTools.register(ID, new Stateless(), "fail", ToolEffect.unclassified()).binding().callback();
        assertThatThrownBy(() -> callback.call("{}")).hasMessage("JAVA_TOOL_FAILED").hasNoCause();
    }
    public static class Stateless {
        @Tool public String echo(String value) { return value; }
        @Tool public String fail() { throw new IllegalStateException("secret-canary"); }
        @Tool public byte[] binary() { return new byte[]{0, 1, (byte)255}; }
        @Tool public java.util.Map<String, byte[]> structured() { return java.util.Map.of("data", binary()); }
    }
    public static class Mutable extends Stateless { int counter; }
    public static class Direct {
        @Tool(returnDirect = true) public String echo(String value) { return value; }
    }
    public static class Inherited extends Mutable {}
}
