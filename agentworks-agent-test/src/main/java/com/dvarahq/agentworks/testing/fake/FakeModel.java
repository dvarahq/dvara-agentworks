package com.dvarahq.agentworks.testing.fake;

import com.dvarahq.agentworks.gateway.*;
import com.dvarahq.agentworks.gateway.fake.DeterministicFakeGatewayAdapter;
import com.dvarahq.agentworks.gateway.fake.FakeReply;
import com.dvarahq.agentworks.testing.simulation.*;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/**
 * Seed-aware facade over the gateway conformance fake shipped by {@code agentworks-gateway-api}. AgentTest deliberately
 * does not maintain a second fake model implementation.
 */
public final class FakeModel {
    public record Request(String userMessage, long seed) {}
    public record Reply(String answer, long inputTokens, long outputTokens) {
        public Reply { if (answer == null || inputTokens < 0 || outputTokens < 0) throw new IllegalArgumentException("invalid reply"); }
    }
    private final String version;
    private final Function<Request, Reply> script;
    private final DeterministicFakeGatewayAdapter adapter;
    private final SimulationFaultInjector faults;
    private final long environmentSeed;
    private final ThreadLocal<Long> invocationSeed = new ThreadLocal<>();
    private final List<Request> requests = new ArrayList<>();
    public FakeModel(String version, Function<Request, Reply> script) {
        this(version, script, SimulationFaultInjector.none(), 0);
    }
    private FakeModel(String version, Function<Request, Reply> script, SimulationFaultInjector faults, long environmentSeed) {
        if (version == null || version.isBlank()) throw new IllegalArgumentException("version is required");
        this.version = version; this.script = Objects.requireNonNull(script); this.faults = Objects.requireNonNull(faults);
        this.environmentSeed = environmentSeed;
        this.adapter = DeterministicFakeGatewayAdapter.builder("agent-test")
                .model("agent-test", version)
                .script(invocation -> {
                    String user = invocation.request().messages().stream()
                            .filter(message -> message.role() == ModelMessage.Role.USER)
                            .reduce((left, right) -> right).map(ModelMessage::content).orElse("");
                    Reply reply = this.script.apply(new Request(user, invocationSeed.get()));
                    return FakeReply.text(List.of(reply.answer()), ModelUsage.reported(
                            reply.inputTokens(), reply.outputTokens(), null, null, null));
                }).build();
    }
    public synchronized Reply invoke(Request request) {
        faults.before(new DependencyCall("model", version, "invoke", request.seed()));
        requests.add(request); invocationSeed.set(request.seed());
        try {
            AdapterResult result = adapter.invoke(new AdapterInvocation(version,
                    ModelRequest.of(ModelMessage.user(request.userMessage())),
                    new InvocationPlan(false, false, false, true, true), noCredential(),
                    CancellationSignal.create(), Duration.ofSeconds(5)), new ChunkSink() {
                        @Override public void requestSent() {}
                        @Override public void emit(String delta) {}
                    });
            ModelUsage usage = Objects.requireNonNull(result.usage(), "fake usage");
            return new Reply(result.content(), usage.inputTokens(), usage.outputTokens());
        } finally { invocationSeed.remove(); }
    }
    public String version() { return version; }
    public synchronized List<Request> requests() { return List.copyOf(requests); }
    public FakeModel fresh() { return new FakeModel(version, script, faults.fresh(), environmentSeed); }
    public FakeModel withFaults(SimulationFaultInjector value, long seed) { return new FakeModel(version, script, value, seed); }
    public static FakeModel echo(String version) {
        return new FakeModel(version, request -> new Reply("echo: " + request.userMessage(), words(request.userMessage()), words(request.userMessage()) + 1));
    }
    private static long words(String value) { return value.isBlank() ? 0 : value.trim().split("\\s+").length; }
    private static GatewayCredential noCredential() {
        return new GatewayCredential() {
            @Override public boolean present() { return false; }
            @Override public <T> T withMaterial(Function<char[], T> use) {
                throw new GatewayException(GatewayErrorCode.CREDENTIAL_UNAVAILABLE, "fake has no credential");
            }
        };
    }
}
