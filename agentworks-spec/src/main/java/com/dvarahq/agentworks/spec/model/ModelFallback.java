package com.dvarahq.agentworks.spec.model;

/** A logical fallback binding resolved to a concrete identity when a release is created. */
public record ModelFallback(String gateway, String model) { }
