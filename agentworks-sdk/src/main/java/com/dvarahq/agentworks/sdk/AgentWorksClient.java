package com.dvarahq.agentworks.sdk;

import com.dvarahq.agentworks.sdk.stream.RunEventPublisher;
import com.dvarahq.agentworks.sdk.stream.RunStreamOptions;
import com.dvarahq.agentworks.sdk.stream.RunStreamTransport;
import com.dvarahq.agentworks.sdk.stream.TypedRunEvent;
import com.dvarahq.agentworks.spec.AgentSpecCodec;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Flow;

/** Thread-safe entry point for the versioned Java SDK. */
public final class AgentWorksClient implements AutoCloseable {
    private final AgentWorksTransport transport;
    private final RunStreamTransport streamTransport;
    private final AgentSpecCodec specs = AgentSpecCodec.create();

    public AgentWorksClient(AgentWorksTransport transport, RunStreamTransport streamTransport) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.streamTransport = Objects.requireNonNull(streamTransport, "streamTransport");
    }

    public static Builder builder(URI baseUri) { return new Builder(baseUri); }
    public AgentSpecCodec specs() { return specs; }
    public ApiDocument execute(ApiCall call) { return transport.execute(call); }

    /** A cold publisher: each subscriber owns an independently cancellable reconnect loop. */
    public Flow.Publisher<TypedRunEvent> streamRun(String runId, RunStreamOptions options) {
        return new RunEventPublisher(runId, options, streamTransport);
    }

    @Override public void close() { transport.close(); }

    public static final class Builder {
        private final URI baseUri;
        private HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        private AccessTokenProvider accessTokenProvider;
        private Duration requestTimeout = Duration.ofSeconds(30);

        private Builder(URI baseUri) {
            this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
            if (!"http".equalsIgnoreCase(baseUri.getScheme()) && !"https".equalsIgnoreCase(baseUri.getScheme())) {
                throw new IllegalArgumentException("baseUri must use http or https");
            }
        }

        public Builder httpClient(HttpClient value) { this.httpClient = Objects.requireNonNull(value); return this; }
        public Builder accessTokenProvider(AccessTokenProvider value) { this.accessTokenProvider = value; return this; }
        public Builder requestTimeout(Duration value) {
            if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException("requestTimeout");
            this.requestTimeout = value; return this;
        }
        public AgentWorksClient build() {
            JdkAgentWorksTransport transport = new JdkAgentWorksTransport(baseUri, httpClient, accessTokenProvider, requestTimeout);
            return new AgentWorksClient(transport, transport);
        }
    }
}
