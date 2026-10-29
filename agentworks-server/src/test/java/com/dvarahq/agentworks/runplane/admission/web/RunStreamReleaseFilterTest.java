package com.dvarahq.agentworks.runplane.admission.web;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #192: the release filter starts a stream only after the whole chain returned with the request asynchronous. */
class RunStreamReleaseFilterTest {
    private static final String STREAM = "/api/v1/runs/0b7d6a1e-8b0e-4b6f-9d3e-2f4d1c5a7e90/stream";
    private final RunStreamReleaseFilter filter = new RunStreamReleaseFilter();

    @Test
    void aStreamIsReleasedOnlyAfterTheChainReturned() throws Exception {
        List<String> order = new ArrayList<>();
        MockHttpServletRequest request = stream("GET", STREAM);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            RunStreamReleaseFilter.require(req).hold(async -> order.add("released async=" + async));
            request.setAsyncStarted(true);
            order.add("chain returned");
        });
        assertThat(order).containsExactly("chain returned", "released async=true");
        assertThat(RunStreamReleaseFilter.pending(request)).isNull();
    }

    @Test
    void aRequestThatDidNotGoAsynchronousOrWhoseChainFailedCancelsItsStream() throws Exception {
        List<Boolean> released = new ArrayList<>();
        filter.doFilter(stream("GET", STREAM), new MockHttpServletResponse(),
                (req, res) -> RunStreamReleaseFilter.require(req).hold(released::add));

        MockHttpServletRequest failing = stream("GET", STREAM);
        assertThatThrownBy(() -> filter.doFilter(failing, new MockHttpServletResponse(), (req, res) -> {
            RunStreamReleaseFilter.require(req).hold(released::add);
            failing.setAsyncStarted(true);
            throw new IllegalStateException("a filter failed after the chain");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(released).containsExactly(false, false);
    }

    /** Only the original dispatch of a stream request is wrapped; every other request passes straight through. */
    @Test
    void onlyTheOriginalDispatchOfAStreamRequestIsWrapped() throws Exception {
        MockHttpServletRequest dispatch = stream("GET", STREAM);
        dispatch.setDispatcherType(DispatcherType.ASYNC);
        MockHttpServletRequest other = stream("GET", "/api/v1/runs");
        MockHttpServletRequest post = stream("POST", STREAM);
        List<Object> seen = new ArrayList<>();
        for (MockHttpServletRequest request : List.of(dispatch, other, post)) {
            filter.doFilter(request, new MockHttpServletResponse(),
                    (req, res) -> seen.add(String.valueOf(RunStreamReleaseFilter.pending(req))));
        }
        assertThat(seen).containsExactly("null", "null", "null");
    }

    /** A stream request the filter does not wrap is refused, so the #192 race cannot silently return. */
    @Test
    void aStreamRequestTheFilterDoesNotWrapIsRefused() {
        assertThatThrownBy(() -> RunStreamReleaseFilter.require(stream("GET", STREAM)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RunStreamReleaseFilter");
    }

    /**
     * #200: the filter matches the decoded path the dispatcher servlet maps, so an encoded spelling of a stream request
     * is wrapped as well instead of reaching the controller unwrapped.
     */
    @Test
    void anEncodedStreamPathIsWrappedToo() throws Exception {
        MockHttpServletRequest encoded = stream("GET", STREAM);
        encoded.setRequestURI(STREAM.replace("/stream", "/%73tream"));
        List<Object> seen = new ArrayList<>();
        filter.doFilter(encoded, new MockHttpServletResponse(),
                (req, res) -> seen.add(RunStreamReleaseFilter.pending(req) != null));
        assertThat(seen).containsExactly(true);
    }

    /** A request as the container presents it to a filter: the raw URI, and the decoded servlet path. */
    private static MockHttpServletRequest stream(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}
