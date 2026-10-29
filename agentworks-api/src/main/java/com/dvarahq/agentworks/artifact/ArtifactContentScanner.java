package com.dvarahq.agentworks.artifact;

import com.dvarahq.agentworks.domain.artifact.ArtifactDigest;
import com.dvarahq.agentworks.domain.artifact.ArtifactValidation;

import java.io.InputStream;
import java.util.Objects;

/** Content-security boundary invoked on a bounded staged upload before it becomes readable. */
@FunctionalInterface
public interface ArtifactContentScanner {
    ScanResult scan(ScanRequest request);

    record ScanRequest(String mediaType, long size, ArtifactDigest digest, InputStream content) {
        public ScanRequest {
            Objects.requireNonNull(mediaType, "mediaType");
            if (size < 0) throw new IllegalArgumentException("artifact scan size cannot be negative");
            Objects.requireNonNull(digest, "digest");
            Objects.requireNonNull(content, "content");
        }
    }

    record ScanResult(ArtifactValidation validation, String reason) {
        public ScanResult {
            Objects.requireNonNull(validation, "validation");
            if (validation == ArtifactValidation.PENDING) {
                throw new IllegalArgumentException("a scanner must return a terminal decision");
            }
            if (reason == null || reason.isBlank() || reason.length() > 256) {
                throw new IllegalArgumentException("artifact scan reason is invalid");
            }
        }

        public static ScanResult accepted(String reason) {
            return new ScanResult(ArtifactValidation.ACCEPTED, reason);
        }

        public static ScanResult quarantined(String reason) {
            return new ScanResult(ArtifactValidation.QUARANTINED, reason);
        }

        public static ScanResult rejected(String reason) {
            return new ScanResult(ArtifactValidation.REJECTED, reason);
        }
    }
}
