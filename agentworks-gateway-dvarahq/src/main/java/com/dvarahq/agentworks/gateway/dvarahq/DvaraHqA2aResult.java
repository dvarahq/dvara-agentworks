package com.dvarahq.agentworks.gateway.dvarahq;

/** Validated DvaraHQ A2A response. The bounded JSON remains protocol evidence for the caller. */
public record DvaraHqA2aResult(String rpcId, String responseJson, String traceId, String taskId,
        String contextId, String status) { }
