package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.runplane.compiled.CompiledReleaseCache;
import com.dvarahq.agentworks.runplane.compiled.SharedResourceRegistry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Exclusive, fsynced and privacy-safe evidence for cleanup that did not finish in-process. Evidence remains unresolved
 * across restart: arbitrary external resources may outlive a JVM, so a new process must never manufacture a release.
 */
public final class ResourceCleanupJournal implements AutoCloseable {
    static final long MAX_BYTES = 16L * 1024 * 1024;
    static final int MAX_ROW_BYTES = 512;
    private static final Map<Path, SharedJournal> OPEN = new HashMap<>();
    private final Path pending;
    private final SharedJournal shared;
    private final int unresolvedAtStartup;
    private boolean closed;

    public ResourceCleanupJournal(Path pending, Clock clock) {
        this.pending = Objects.requireNonNull(pending, "pending").toAbsolutePath().normalize();
        Objects.requireNonNull(clock, "clock");
        shared = acquire(this.pending);
        unresolvedAtStartup = shared.unresolvedAtStartup;
    }

    private static SharedJournal acquire(Path pending) {
        synchronized (OPEN) {
            SharedJournal alreadyOpen = OPEN.get(pending);
            if (alreadyOpen != null) {
                alreadyOpen.references++;
                return alreadyOpen;
            }
            try {
                Path parent = pending.getParent();
                if (parent != null) Files.createDirectories(parent);
                if (Files.exists(pending) && Files.size(pending) > MAX_BYTES) {
                    throw new IllegalStateException("cleanup evidence journal exceeds its safe read bound");
                }
                FileChannel channel = FileChannel.open(pending, StandardOpenOption.CREATE, StandardOpenOption.READ,
                        StandardOpenOption.WRITE);
                FileLock acquired;
                try { acquired = channel.tryLock(); }
                catch (java.nio.channels.OverlappingFileLockException owned) { acquired = null; }
                if (acquired == null) {
                    channel.close();
                    throw new IllegalStateException("cleanup evidence journal is owned by another process");
                }
                List<String> previous;
                try {
                    previous = Files.readAllLines(pending, StandardCharsets.UTF_8);
                } catch (IOException | RuntimeException failure) {
                    closeAfterOpenFailure(channel, acquired, failure);
                    throw failure;
                }
                if (previous.stream().anyMatch(line -> line.getBytes(StandardCharsets.UTF_8).length > MAX_ROW_BYTES)) {
                    IllegalStateException failure = new IllegalStateException(
                            "cleanup evidence journal contains an oversized row");
                    closeAfterOpenFailure(channel, acquired, failure);
                    throw failure;
                }
                SharedJournal opened = new SharedJournal(channel, acquired,
                        (int) previous.stream().filter(line -> !line.isBlank()).count());
                OPEN.put(pending, opened);
                return opened;
            } catch (IOException failure) {
                throw new IllegalStateException("cleanup evidence journal is unavailable", failure);
            }
        }
    }

    private static void closeAfterOpenFailure(FileChannel channel, FileLock ownership, Throwable failure) {
        try { if (ownership.isValid()) ownership.release(); }
        catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
        try { if (channel.isOpen()) channel.close(); }
        catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
    }

    public int unresolvedAtStartup() { return unresolvedAtStartup; }

    public void record(CompiledReleaseCache.Cleanup cleanup) {
        var key = cleanup.key();
        String correlation = fingerprint(key.release().workspaceId() + "|" + key.release().releaseId() + "|"
                + key.release().manifestVersion() + "|" + key.release().dependencyHash() + "|"
                + key.overlay().deploymentId() + "|" + key.overlay().revisionId() + "|"
                + key.overlay().policyHash() + "|" + key.overlay().dependencyHash());
        record("COMPILED", key.release().workspaceId().toString(), correlation, cleanup.reason(),
                cleanup.activeReferences(), cleanup.observedAt());
    }

    public void record(SharedResourceRegistry.Cleanup cleanup) {
        var key = cleanup.key();
        String correlation = fingerprint(key.kind() + "|" + key.owner() + "|" + key.isolation() + "|"
                + key.workspaceId() + "|" + key.identity() + "|" + key.configurationHash());
        record("SHARED_" + key.kind(), key.workspaceId() == null ? "GLOBAL" : key.workspaceId().toString(),
                correlation, "CLOSE_INCOMPLETE", cleanup.activeLeases(), cleanup.observedAt());
    }

    private void record(String kind, String isolation, String correlation, String reason, int active, Instant observed) {
        byte[] row = (observed + "|" + kind + "|" + isolation + "|" + correlation + "|" + reason + "|" + active + "\n")
                .getBytes(StandardCharsets.UTF_8);
        if (row.length > MAX_ROW_BYTES) throw new IllegalArgumentException("cleanup evidence row exceeds its bound");
        synchronized (shared) {
            if (closed) throw new IllegalStateException("cleanup evidence journal is closed");
            try {
                if (shared.channel.size() + row.length > MAX_BYTES) {
                    throw new IllegalStateException("cleanup evidence journal is full");
                }
                shared.channel.position(shared.channel.size());
                ByteBuffer bytes = ByteBuffer.wrap(row);
                while (bytes.hasRemaining()) shared.channel.write(bytes);
                shared.channel.force(true);
            } catch (IOException failure) {
                throw new IllegalStateException("cleanup evidence journal write failed", failure);
            }
        }
    }

    private static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @Override public void close() {
        synchronized (OPEN) {
            synchronized (shared) {
                if (closed) return;
                closed = true;
                if (--shared.references == 0) {
                    OPEN.remove(pending, shared);
                    shared.close();
                }
            }
        }
    }

    /** One JVM may host several Spring contexts; they share the process-owned descriptor without truncation. */
    private static final class SharedJournal {
        private final FileChannel channel;
        private final FileLock ownership;
        private final int unresolvedAtStartup;
        private int references = 1;

        private SharedJournal(FileChannel channel, FileLock ownership, int unresolvedAtStartup) {
            this.channel = channel;
            this.ownership = ownership;
            this.unresolvedAtStartup = unresolvedAtStartup;
        }

        private void close() {
            try { if (ownership.isValid()) ownership.release(); }
            catch (IOException ignored) { }
            try { if (channel.isOpen()) channel.close(); }
            catch (IOException ignored) { }
        }
    }
}
