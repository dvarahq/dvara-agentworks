package com.dvarahq.agentworks.replay;

/** The OSS 1.0 replay modes. */
public enum ReplayMode {
    /** Reconstruct the recorded terminal output without invoking a model or an effect. */
    EXACT,
    /** Invoke the release-pinned model while recorded external outcomes remain substitutions. */
    MODEL_REPLAY
}
