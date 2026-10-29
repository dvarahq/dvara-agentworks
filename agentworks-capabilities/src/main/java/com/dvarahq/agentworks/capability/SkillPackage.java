package com.dvarahq.agentworks.capability;

import java.util.List;
import java.util.Objects;

/** Untrusted fetched package. The resolver validates every entry before exposing content. */
public record SkillPackage(List<Entry> entries) {
    public record Entry(String path, byte[] content, boolean symbolicLink) {
        public Entry {
            Objects.requireNonNull(path, "path");
            content = Objects.requireNonNull(content, "content").clone();
        }
        @Override public byte[] content() { return content.clone(); }
    }
    public SkillPackage { entries = List.copyOf(Objects.requireNonNull(entries, "entries")); }
}
