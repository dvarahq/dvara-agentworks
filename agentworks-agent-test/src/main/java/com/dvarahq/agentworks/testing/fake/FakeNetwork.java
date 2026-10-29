package com.dvarahq.agentworks.testing.fake;

import java.net.URI;
import java.util.*;

/** Exact-route in-memory network. Unregistered destinations fail closed. */
public final class FakeNetwork {
    public record Response(int status, String body) { public Response { if (status < 100 || status > 599) throw new IllegalArgumentException("status"); } }
    private final Map<URI, Response> routes = new LinkedHashMap<>();
    private final List<URI> requests = new ArrayList<>();
    public synchronized FakeNetwork route(URI uri, Response response) { routes.put(Objects.requireNonNull(uri), Objects.requireNonNull(response)); return this; }
    public synchronized Response get(URI uri) {
        requests.add(uri); Response response = routes.get(uri);
        if (response == null) throw new IllegalStateException("FAKE_NETWORK_ROUTE_MISSING: " + uri);
        return response;
    }
    public synchronized List<URI> requests() { return List.copyOf(requests); }
    public synchronized FakeNetwork fresh() {
        FakeNetwork copy = new FakeNetwork(); routes.forEach(copy::route); return copy;
    }
}
