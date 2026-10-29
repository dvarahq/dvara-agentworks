package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.runplane.execution.RunEventCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #181 low 4 (refuted): the executor's storable-output pre-check validates only {@code {"state": checkpoint}}, not the
 * rest of the checkpoint envelope the engine writes. It cannot let {@code PAYLOAD_TOO_LARGE} escape: the output is
 * bounded at {@link SpringAiAgentExecutor#MAX_OUTPUT_BYTES}, and every other envelope field is bounded and small, so
 * even the largest admitted output in a worst-case envelope stays within {@link RunEventCodec#MAX_PAYLOAD_BYTES}. The
 * pre-check's nesting is the envelope's own ({@code state} is one level below the root), so depth is covered too.
 * This test keeps that margin from eroding unnoticed.
 */
class CheckpointEnvelopeBoundTest {

    @Test
    void theLargestAdmittedOutputFitsAWorstCaseCheckpointEnvelope() {
        JsonMapper json = JsonMapper.builder().build();
        String framing = json.writeValueAsString(Map.of("type", "text", "content", ""));
        String content = "x".repeat(SpringAiAgentExecutor.MAX_OUTPUT_BYTES - framing.length());
        Map<String, Object> output = Map.of("type", "text", "content", content);
        assertThat(json.writeValueAsBytes(output)).hasSize(SpringAiAgentExecutor.MAX_OUTPUT_BYTES);

        // The engine's checkpoint envelope (DurableRunEngine.checkpoint), with every bounded field at its largest.
        Map<String, Object> usage = new LinkedHashMap<>();
        for (String counter : List.of("inputTokens", "outputTokens", "totalTokens", "modelCalls", "toolCalls",
                "runtimeMillis")) {
            usage.put(counter, Long.MAX_VALUE);
        }
        usage.put("estimatedCostUsd", "9".repeat(64) + "." + "9".repeat(64));
        Map<String, Object> limits = new LinkedHashMap<>();
        for (String limit : List.of("maxTotalTokens", "maxModelCalls", "maxToolCalls", "maxRuntimeMillis")) {
            limits.put(limit, Long.MAX_VALUE);
        }
        limits.put("maxCostUsd", "9".repeat(64) + "." + "9".repeat(64));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("checkpointVersion", Integer.MAX_VALUE);
        envelope.put("state", Map.of("phase", "RESPONDED", "output", output));
        envelope.put("eventSequence", Long.MAX_VALUE);
        envelope.put("usage", usage);
        envelope.put("limits", limits);
        // The Spring AI executor records no modeled effects, so none is ever pending at its checkpoint.
        envelope.put("pendingEffects", List.of());
        envelope.put("pins", Map.of("releaseId", UUID.randomUUID().toString(), "deploymentId",
                UUID.randomUUID().toString(), "revisionId", UUID.randomUUID().toString(), "cohortId",
                UUID.randomUUID().toString(), "versionId", UUID.randomUUID().toString()));

        String canonical = RunEventCodec.standard().canonicalPayload(envelope);

        assertThat(canonical.getBytes(StandardCharsets.UTF_8).length).isLessThan(RunEventCodec.MAX_PAYLOAD_BYTES);
    }
}
