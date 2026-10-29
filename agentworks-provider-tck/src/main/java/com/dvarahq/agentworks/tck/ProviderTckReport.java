package com.dvarahq.agentworks.tck;

import java.util.List;

public record ProviderTckReport(boolean passed, List<String> checks) {
    public ProviderTckReport { checks = List.copyOf(checks); }
}
