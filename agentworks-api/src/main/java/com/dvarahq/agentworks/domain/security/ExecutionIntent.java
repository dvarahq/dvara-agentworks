package com.dvarahq.agentworks.domain.security;

/** The highest class of work an invocation is currently authorized to attempt. */
public enum ExecutionIntent {
    OBSERVE, ANALYZE, PROPOSE, EXECUTE
}
