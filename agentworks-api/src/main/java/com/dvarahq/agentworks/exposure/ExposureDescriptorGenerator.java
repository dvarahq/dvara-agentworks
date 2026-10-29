package com.dvarahq.agentworks.exposure;

import java.util.Map;

/** Generates protocol claims from authoritative immutable inputs rather than accepting hand-authored descriptors. */
public interface ExposureDescriptorGenerator {
    Map<String, Object> generate(ExposureSpec exposure, String releaseManifestJson, String inputSchema,
            String outputSchema);
}
