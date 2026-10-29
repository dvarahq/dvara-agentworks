package com.dvarahq.agentworks.observability;

import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "management.otlp.metrics.export.step=1h")
@ActiveProfiles("otlp")
class OtlpProfileTest {
    @Autowired ApplicationContext context;

    @Test
    void pushProfileNeedsNoPrometheusBackend() {
        assertThat(context.getBeansOfType(OtlpMeterRegistry.class)).hasSize(1);
        assertThat(context.getBeansOfType(PrometheusMeterRegistry.class)).isEmpty();
        assertThat(context.getBeansOfType(io.micrometer.tracing.Tracer.class)).isNotEmpty();
    }
}
