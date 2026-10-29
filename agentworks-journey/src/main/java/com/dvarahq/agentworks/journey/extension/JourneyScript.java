package com.dvarahq.agentworks.journey.extension;

import com.dvarahq.agentworks.gateway.AdapterInvocation;
import com.dvarahq.agentworks.gateway.ModelMessage;
import com.dvarahq.agentworks.gateway.ModelUsage;
import com.dvarahq.agentworks.gateway.ToolCall;
import com.dvarahq.agentworks.gateway.fake.DeterministicFakeGatewayAdapter;
import com.dvarahq.agentworks.gateway.fake.FakeReply;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The deterministic reference agent's model: a script for {@link DeterministicFakeGatewayAdapter}, chosen by the
 * last user message. Every answer is a pure function of the request and of whether the journey's hold file exists.
 * <ul>
 *   <li>{@code tool <name> <entry>}: the first round requests tool {@code name} with {@code {"entry": entry}}; once the
 *       conversation holds a tool result, the answer is {@code recorded <result>}. The name is sent as given, so a
 *       name the server has not registered is how the journey asks for a tool it may not use.</li>
 *   <li>{@code toolslow <name> <entry>}: like {@code tool}, but the answer after the tool result is the slow stream
 *       below. The journey kills the server during that answer to show what recovery repeats after a tool ran.</li>
 *   <li>{@code slow <label>}: a streamed answer. While the hold file exists it is 600 chunks, one every 100 ms (a
 *       minute of streaming, so the journey can kill the server mid-stream); otherwise four quick chunks.</li>
 *   <li>{@code hold <label>}: the provider does not answer while the hold file exists (at most ten minutes, longer than any journey wait), then
 *       echoes. The journey uses it to keep a run in flight across a promotion.</li>
 *   <li>anything else: the fake's default echo.</li>
 * </ul>
 */
public final class JourneyScript implements Function<AdapterInvocation, FakeReply> {
    static final Duration HOLD_LIMIT = Duration.ofMinutes(10);

    private final Path holdFile;

    public JourneyScript(Path holdFile) {
        this.holdFile = Objects.requireNonNull(holdFile, "holdFile");
    }

    @Override
    public FakeReply apply(AdapterInvocation invocation) {
        String prompt = lastUserMessage(invocation);
        String[] words = prompt.strip().split("\\s+");
        if (words.length == 3 && (words[0].equals("tool") || words[0].equals("toolslow"))) {
            String result = invocation.request().messages().stream()
                    .filter(message -> message.role() == ModelMessage.Role.TOOL)
                    .map(ModelMessage::content).reduce((first, second) -> second).orElse(null);
            if (result == null) {
                return FakeReply.text(List.of(), ModelUsage.reported(3, 0, null, null, null))
                        .withToolCalls(List.of(new ToolCall("call-" + words[2], words[1],
                                "{\"entry\":\"" + words[2] + "\"}")));
            }
            if (words[0].equals("toolslow")) {
                return slow();
            }
            return FakeReply.text(List.of("recorded ", result), ModelUsage.reported(4, 2, null, null, null));
        }
        if (words.length == 2 && words[0].equals("slow")) {
            return slow();
        }
        if (words.length == 2 && words[0].equals("hold")) {
            long deadline = System.nanoTime() + HOLD_LIMIT.toNanos();
            while (Files.exists(holdFile) && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return DeterministicFakeGatewayAdapter.echo(invocation);
    }

    private FakeReply slow() {
        boolean held = Files.exists(holdFile);
        int count = held ? 600 : 4;
        List<String> chunks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            chunks.add("slow-%03d ".formatted(index));
        }
        return FakeReply.text(chunks, ModelUsage.reported(2, count, null, null, null))
                .withChunkDelay(Duration.ofMillis(held ? 100 : 10));
    }

    private static String lastUserMessage(AdapterInvocation invocation) {
        String content = invocation.request().messages().stream()
                .filter(message -> message.role() == ModelMessage.Role.USER)
                .map(ModelMessage::content).filter(Objects::nonNull)
                .reduce((first, second) -> second).orElse("");
        return scriptedInput(content);
    }

    static String scriptedInput(String message) {
        String marker = "[USER_INPUT:run-input]\n";
        return message.startsWith(marker) ? message.substring(marker.length()) : message;
    }
}
