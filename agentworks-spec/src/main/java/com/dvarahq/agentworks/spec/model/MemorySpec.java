package com.dvarahq.agentworks.spec.model;

public record MemorySpec(MemoryTier conversation, MemoryTier working, MemoryTier semantic, MemoryTier episodic) {
}
