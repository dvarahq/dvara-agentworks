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
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("prometheus")
class PrometheusProfileTest {
    @Autowired ApplicationContext context;

    @Test
    void scrapeProfileNeedsNoOtlpBackend() {
        assertThat(context.getBeansOfType(PrometheusMeterRegistry.class)).hasSize(1);
        assertThat(context.getBeansOfType(OtlpMeterRegistry.class)).isEmpty();
    }
}
