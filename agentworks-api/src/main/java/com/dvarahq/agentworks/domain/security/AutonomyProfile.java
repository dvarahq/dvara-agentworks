package com.dvarahq.agentworks.domain.security;

/** Default initiative and escalation behavior; hard policy and guardrails always take precedence. */
public enum AutonomyProfile {
    READ_ONLY, ASSISTED, SUPERVISED, AUTONOMOUS, STRICT
}
