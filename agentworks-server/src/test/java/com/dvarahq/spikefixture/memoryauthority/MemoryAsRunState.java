package com.dvarahq.spikefixture.memoryauthority;

import com.dvarahq.agentworks.memory.MemoryRecord;

/** Deliberate negative fixture proving issue #49's memory-authority architecture rule is live. */
public record MemoryAsRunState(MemoryRecord state) {
}
