package com.dvarahq.agentworks.identity.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBodyLimitFilterTest {
    @Test
    void artifactStreamingExemptionUsesTheServletPathUnderAContextPath() {
        var request = new MockHttpServletRequest("POST", "/agentworks/api/v1/artifacts");
        request.setContextPath("/agentworks");
        request.setServletPath("/api/v1/artifacts");

        assertThat(new RequestBodyLimitFilter(64).shouldNotFilter(request)).isTrue();
    }

    @Test
    void similarlyPrefixedRoutesAreNotExempt() {
        var request = new MockHttpServletRequest("POST", "/api/v1/artifacts/import");
        request.setServletPath("/api/v1/artifacts/import");

        assertThat(new RequestBodyLimitFilter(64).shouldNotFilter(request)).isFalse();
    }
}
