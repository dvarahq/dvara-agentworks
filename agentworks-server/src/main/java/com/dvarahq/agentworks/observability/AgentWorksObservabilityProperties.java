package com.dvarahq.agentworks.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

/** Bounded, fail-closed observability configuration. Exporters are optional and never become runtime authority. */
@ConfigurationProperties("agentworks.observability")
public class AgentWorksObservabilityProperties {
    private static final Set<String> SUPPORTED_REQUIRED_DEPENDENCIES = Set.of("database");

    private List<String> requiredDependencies = List.of("database");
    private int maxTagValues = 64;
    private final SupportBundle supportBundle = new SupportBundle();

    public List<String> getRequiredDependencies() { return requiredDependencies; }
    public void setRequiredDependencies(List<String> requiredDependencies) {
        this.requiredDependencies = requiredDependencies == null ? List.of() : List.copyOf(requiredDependencies);
    }
    public int getMaxTagValues() { return maxTagValues; }
    public void setMaxTagValues(int maxTagValues) { this.maxTagValues = maxTagValues; }
    public SupportBundle getSupportBundle() { return supportBundle; }

    public void validate() {
        if (!SUPPORTED_REQUIRED_DEPENDENCIES.containsAll(requiredDependencies)) {
            throw new IllegalArgumentException("required dependencies may contain only: database");
        }
        if (maxTagValues < 8 || maxTagValues > 256) {
            throw new IllegalArgumentException("observability max tag values must be between 8 and 256");
        }
        supportBundle.validate();
    }

    public static final class SupportBundle {
        private int maxBytes = 256 * 1024;
        private int maxEvents = 100;
        public int getMaxBytes() { return maxBytes; }
        public void setMaxBytes(int maxBytes) { this.maxBytes = maxBytes; }
        public int getMaxEvents() { return maxEvents; }
        public void setMaxEvents(int maxEvents) { this.maxEvents = maxEvents; }
        private void validate() {
            if (maxBytes < 16 * 1024 || maxBytes > 1024 * 1024) {
                throw new IllegalArgumentException("support bundle maximum must be between 16 KiB and 1 MiB");
            }
            if (maxEvents < 0 || maxEvents > 500) {
                throw new IllegalArgumentException("support bundle event maximum must be between 0 and 500");
            }
        }
    }
}
