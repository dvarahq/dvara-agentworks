package com.dvarahq.agentworks.gateway;

/**
 * Receives ordered chunks one at a time, never concurrently, possibly on a gateway-owned virtual thread. A listener
 * may cancel the invocation's {@link CancellationSignal} from here or from any other thread; no chunk delivery starts
 * after the cancellation is observed. If the listener throws, the invocation stops and the caller receives that
 * exception.
 */
@FunctionalInterface
public interface ModelChunkListener {
    void onChunk(ModelChunk chunk);
}
