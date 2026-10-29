package com.dvarahq.agentworks.domain.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Closeable bounded stream for one authorized artifact read. */
public record ArtifactContent(ArtifactMetadata metadata, long offset, long length, InputStream stream)
        implements AutoCloseable {
    public ArtifactContent {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(stream, "stream");
        if (offset < 0 || length < 0 || offset > metadata.size() || length > metadata.size() - offset) {
            throw new IllegalArgumentException("artifact content range is invalid");
        }
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }

    @Override
    public String toString() {
        return "ArtifactContent[metadata=" + metadata + ", offset=" + offset + ", length=" + length + "]";
    }
}
