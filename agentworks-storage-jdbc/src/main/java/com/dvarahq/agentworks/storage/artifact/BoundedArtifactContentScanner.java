package com.dvarahq.agentworks.storage.artifact;

import com.dvarahq.agentworks.artifact.ArtifactContentScanner;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Built-in fail-closed content policy. It recognizes safe ordinary content, inspects ZIP archives under strict
 * expansion limits, and quarantines opaque binaries, executable formats, malware canaries, and archive types that
 * need an external scanner. It is intentionally not advertised as a general malware engine.
 */
public final class BoundedArtifactContentScanner implements ArtifactContentScanner {
    private static final byte[] EICAR = "EICAR-STANDARD-ANTIVIRUS-TEST-FILE".getBytes(StandardCharsets.US_ASCII);
    private static final Set<String> ZIP_TYPES = Set.of("application/zip", "application/x-zip-compressed",
            "application/java-archive");
    private static final Set<String> EXTERNAL_ARCHIVES = Set.of("application/gzip", "application/x-gzip",
            "application/x-tar", "application/x-7z-compressed", "application/vnd.rar");
    private static final int BUFFER_SIZE = 8192;

    private final int maximumArchiveEntries;
    private final long maximumExpandedBytes;
    private final int maximumExpansionRatio;

    public BoundedArtifactContentScanner() {
        this(1_000, 256L * 1024 * 1024, 100);
    }

    public BoundedArtifactContentScanner(int maximumArchiveEntries, long maximumExpandedBytes,
            int maximumExpansionRatio) {
        if (maximumArchiveEntries < 1 || maximumArchiveEntries > 100_000) {
            throw new IllegalArgumentException("maximum archive entries is invalid");
        }
        if (maximumExpandedBytes < 1 || maximumExpansionRatio < 1) {
            throw new IllegalArgumentException("archive expansion bounds must be positive");
        }
        this.maximumArchiveEntries = maximumArchiveEntries;
        this.maximumExpandedBytes = maximumExpandedBytes;
        this.maximumExpansionRatio = maximumExpansionRatio;
    }

    @Override
    public ScanResult scan(ScanRequest request) {
        try (BufferedInputStream input = new BufferedInputStream(request.content())) {
            input.mark(8);
            byte[] prefix = input.readNBytes(4);
            input.reset();
            if (isExecutable(prefix)) return ScanResult.quarantined("EXECUTABLE_CONTENT");
            boolean zip = isZip(prefix);
            if (zip && !ZIP_TYPES.contains(request.mediaType())) {
                return ScanResult.quarantined("MEDIA_TYPE_MISMATCH");
            }
            if (!zip && ZIP_TYPES.contains(request.mediaType())) {
                return ScanResult.quarantined("MEDIA_TYPE_MISMATCH");
            }
            if (EXTERNAL_ARCHIVES.contains(request.mediaType()) || "application/octet-stream".equals(request.mediaType())) {
                return ScanResult.quarantined("EXTERNAL_SCANNER_REQUIRED");
            }
            return zip ? scanZip(input, request.size()) : scanOrdinary(input);
        } catch (IOException unreadable) {
            return ScanResult.quarantined("SCAN_UNAVAILABLE");
        }
    }

    private ScanResult scanOrdinary(InputStream input) throws IOException {
        return contains(input, EICAR) ? ScanResult.quarantined("MALWARE_DETECTED")
                : ScanResult.accepted("BUILTIN_POLICY_ACCEPTED");
    }

    private ScanResult scanZip(InputStream source, long compressedBytes) throws IOException {
        int entries = 0;
        long expanded = 0;
        try (ZipInputStream zip = new ZipInputStream(source)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (++entries > maximumArchiveEntries) return ScanResult.quarantined("ARCHIVE_ENTRY_LIMIT");
                if (unsafeEntry(entry.getName())) return ScanResult.quarantined("ARCHIVE_PATH_ESCAPE");
                if (entry.isDirectory()) continue;
                PatternCounter counter = new PatternCounter(EICAR);
                byte[] buffer = new byte[BUFFER_SIZE];
                for (int read; (read = zip.read(buffer)) >= 0;) {
                    if (read == 0) continue;
                    expanded += read;
                    if (expanded > maximumExpandedBytes
                            || (compressedBytes > 0 && expanded > compressedBytes * maximumExpansionRatio)) {
                        return ScanResult.quarantined("ARCHIVE_EXPANSION_LIMIT");
                    }
                    if (counter.accept(buffer, read)) return ScanResult.quarantined("MALWARE_DETECTED");
                }
            }
        } catch (java.util.zip.ZipException invalid) {
            return ScanResult.quarantined("MALFORMED_ARCHIVE");
        }
        return ScanResult.accepted("BUILTIN_ARCHIVE_ACCEPTED");
    }

    private static boolean contains(InputStream input, byte[] pattern) throws IOException {
        PatternCounter counter = new PatternCounter(pattern);
        byte[] buffer = new byte[BUFFER_SIZE];
        for (int read; (read = input.read(buffer)) >= 0;) {
            if (read > 0 && counter.accept(buffer, read)) return true;
        }
        return false;
    }

    private static boolean unsafeEntry(String name) {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0) return true;
        try {
            Path path = Path.of(name);
            return path.isAbsolute() || !path.normalize().equals(path) || name.startsWith("../")
                    || name.contains("/../");
        } catch (java.nio.file.InvalidPathException invalid) {
            return true;
        }
    }

    private static boolean isZip(byte[] prefix) {
        return prefix.length >= 4 && prefix[0] == 'P' && prefix[1] == 'K'
                && ((prefix[2] == 3 && prefix[3] == 4) || (prefix[2] == 5 && prefix[3] == 6)
                || (prefix[2] == 7 && prefix[3] == 8));
    }

    private static boolean isExecutable(byte[] prefix) {
        if (prefix.length < 2) return false;
        if (prefix[0] == 'M' && prefix[1] == 'Z') return true;
        if (prefix.length < 4) return false;
        if (prefix[0] == 0x7f && prefix[1] == 'E' && prefix[2] == 'L' && prefix[3] == 'F') return true;
        int magic = ((prefix[0] & 0xff) << 24) | ((prefix[1] & 0xff) << 16)
                | ((prefix[2] & 0xff) << 8) | (prefix[3] & 0xff);
        return magic == 0xcafebabe || magic == 0xfeedface || magic == 0xfeedfacf
                || magic == 0xcefaedfe || magic == 0xcffaedfe;
    }

    private static final class PatternCounter {
        private final byte[] pattern;
        private int matched;

        private PatternCounter(byte[] pattern) {
            this.pattern = pattern;
        }

        private boolean accept(byte[] bytes, int length) {
            for (int index = 0; index < length; index++) {
                if (bytes[index] == pattern[matched]) {
                    if (++matched == pattern.length) return true;
                } else {
                    matched = bytes[index] == pattern[0] ? 1 : 0;
                }
            }
            return false;
        }
    }
}
