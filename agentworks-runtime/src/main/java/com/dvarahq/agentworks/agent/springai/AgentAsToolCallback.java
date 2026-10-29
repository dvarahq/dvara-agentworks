package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.child.ChildFailurePolicy;
import com.dvarahq.agentworks.child.ChildRunBudget;
import com.dvarahq.agentworks.child.ChildRunException;
import com.dvarahq.agentworks.child.ChildRunRequest;
import com.dvarahq.agentworks.runplane.execution.AgentAsTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Built-in run-bound callback; the executor wraps it in the ordinary policy/guardrail/event pipeline. */
public final class AgentAsToolCallback implements RunBoundToolCallback {
    public static final String CAPABILITY = "agentworks:delegate-agent:1.0.0";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ToolDefinition DEFINITION = ToolDefinition.builder().name("delegate_agent")
            .description("Delegate a bounded task to another approved agent deployment")
            .inputSchema("""
                    {"type":"object","additionalProperties":false,
                     "required":["targetDeploymentId","expectedReleaseId","task","budget"],
                     "properties":{"targetDeploymentId":{"type":"string","format":"uuid"},
                     "expectedReleaseId":{"type":"string","format":"uuid"},"task":{"type":"string"},
                     "context":{"type":"object"},"allowedTools":{"type":"array","items":{"type":"string"}},
                     "budget":{"type":"object"},"failurePolicy":{"enum":["RETURN_OUTCOME","FAIL_PARENT"]}}}
                    """).build();

    private final ToolRunScope scope;
    public AgentAsToolCallback() { this.scope = null; }
    private AgentAsToolCallback(ToolRunScope scope) { this.scope = scope; }
    @Override public ToolCallback bind(ToolRunScope scope) { return new AgentAsToolCallback(scope); }
    @Override public ToolDefinition getToolDefinition() { return DEFINITION; }
    @Override public String call(String input) { return call(input, null); }
    @Override public String call(String input, ToolContext toolContext) {
        if (scope == null) throw new ToolAdapterException("CHILD_RUN_SCOPE_REQUIRED");
        try {
            JsonNode node = JSON.readTree(input);
            ChildRunBudget budget = JSON.treeToValue(node.get("budget"), ChildRunBudget.class);
            Map<String, Object> context = node.has("context")
                    ? JSON.convertValue(node.get("context"), new tools.jackson.core.type.TypeReference<>() {}) : Map.of();
            Set<String> tools = node.has("allowedTools")
                    ? JSON.convertValue(node.get("allowedTools"), new tools.jackson.core.type.TypeReference<>() {}) : Set.of();
            String durableSlot = ToolCallIdentityRegistry.durableSlot(toolContext);
            if (durableSlot == null) throw new ToolAdapterException("CHILD_TOOL_CALL_SLOT_REQUIRED");
            ChildFailurePolicy failurePolicy = ChildFailurePolicy.valueOf(
                    node.path("failurePolicy").asString("RETURN_OUTCOME"));
            ChildRunRequest request = new ChildRunRequest(UUID.fromString(node.path("targetDeploymentId").asString()),
                    UUID.fromString(node.path("expectedReleaseId").asString()), node.path("task").asString(), context,
                    tools, budget, failurePolicy, durableSlot);
            AgentAsTool.Result result = scope.context().invokeChild(request);
            if (result instanceof AgentAsTool.Result.Blocked blocked) {
                throw new ChildRunBlockedException(blocked.relationshipId(), blocked.childRunId());
            }
            if (result instanceof AgentAsTool.Result.Completed completed) {
                return JSON.writeValueAsString(completed.output());
            }
            AgentAsTool.Result.Failed failed = (AgentAsTool.Result.Failed) result;
            if (failurePolicy == ChildFailurePolicy.FAIL_PARENT) {
                throw new ToolAdapterException("CHILD_RUN_" + failed.status());
            }
            return JSON.writeValueAsString(Map.of("relationshipId", failed.relationshipId().toString(),
                    "childRunId", failed.childRunId().toString(), "status", failed.status(),
                    "code", failed.code() == null ? failed.status() : failed.code()));
        } catch (ChildRunBlockedException blocked) {
            throw blocked;
        } catch (ToolAdapterException typed) {
            throw typed;
        } catch (ChildRunException typed) {
            throw new ToolAdapterException(typed.code().name());
        } catch (Exception invalid) {
            throw new ToolAdapterException("CHILD_RUN_REQUEST_INVALID");
        }
    }
}
