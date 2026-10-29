package com.dvarahq.agentworks.storage.artifact;

import com.dvarahq.agentworks.artifact.ArtifactContentScanner.ScanRequest;
import com.dvarahq.agentworks.domain.artifact.ArtifactDigest;
import com.dvarahq.agentworks.domain.artifact.ArtifactValidation;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

final class BoundedArtifactContentScannerTest {
    private static final ArtifactDigest DIGEST = new ArtifactDigest("sha256:" + "0".repeat(64));

    @Test
    void acceptsOrdinaryTextAndBoundedZipContent() throws Exception {
        assertThat(scan(new BoundedArtifactContentScanner(), "text/plain", bytes("safe text")))
                .extracting(result -> result.validation(), result -> result.reason())
                .containsExactly(ArtifactValidation.ACCEPTED, "BUILTIN_POLICY_ACCEPTED");

        byte[] archive = zip("folder/result.txt", "safe result");
        assertThat(scan(new BoundedArtifactContentScanner(), "application/zip", archive).validation())
                .isEqualTo(ArtifactValidation.ACCEPTED);
    }

    @Test
    void quarantinesMalwareExecutablesOpaqueContentAndMediaTypeSpoofing() throws Exception {
        assertQuarantined("text/plain", bytes("prefix EICAR-STANDARD-ANTIVIRUS-TEST-FILE suffix"),
                "MALWARE_DETECTED");
        assertQuarantined("application/octet-stream", new byte[]{0x7f, 'E', 'L', 'F'}, "EXECUTABLE_CONTENT");
        assertQuarantined("application/octet-stream", bytes("opaque"), "EXTERNAL_SCANNER_REQUIRED");
        assertQuarantined("text/plain", zip("safe.txt", "safe"), "MEDIA_TYPE_MISMATCH");
    }

    @Test
    void quarantinesArchiveTraversalExpansionAndMalware() throws Exception {
        assertQuarantined("application/zip", zip("../escape.txt", "safe"), "ARCHIVE_PATH_ESCAPE");
        assertThat(scan(new BoundedArtifactContentScanner(10, 4, 100), "application/zip",
                zip("large.txt", "12345")).reason()).isEqualTo("ARCHIVE_EXPANSION_LIMIT");
        assertQuarantined("application/zip", zip("payload.txt", "EICAR-STANDARD-ANTIVIRUS-TEST-FILE"),
                "MALWARE_DETECTED");
    }

    private static void assertQuarantined(String mediaType, byte[] content, String reason) {
        assertThat(scan(new BoundedArtifactContentScanner(), mediaType, content))
                .extracting(result -> result.validation(), result -> result.reason())
                .containsExactly(ArtifactValidation.QUARANTINED, reason);
    }

    private static com.dvarahq.agentworks.artifact.ArtifactContentScanner.ScanResult scan(
            BoundedArtifactContentScanner scanner, String mediaType, byte[] content) {
        return scanner.scan(new ScanRequest(mediaType, content.length, DIGEST, new ByteArrayInputStream(content)));
    }

    private static byte[] zip(String name, String value) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(bytes(value));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
