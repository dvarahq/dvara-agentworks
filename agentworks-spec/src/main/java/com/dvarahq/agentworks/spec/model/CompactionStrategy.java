package com.dvarahq.agentworks.spec.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum CompactionStrategy {
    @JsonProperty("none") NONE,
    @JsonProperty("sliding-window") SLIDING_WINDOW,
    @JsonProperty("summary") SUMMARY,
    @JsonProperty("hierarchical") HIERARCHICAL,
    @JsonProperty("artifact-aware") ARTIFACT_AWARE
}
