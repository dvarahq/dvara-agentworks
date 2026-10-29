package com.dvarahq.agentworks.artifact.compute;

import com.dvarahq.agentworks.artifact.ArtifactCompute;
import com.dvarahq.agentworks.artifact.ArtifactComputeException;
import com.dvarahq.agentworks.artifact.ArtifactStore;
import com.dvarahq.agentworks.domain.artifact.ArtifactComputeOperator;
import com.dvarahq.agentworks.domain.artifact.ArtifactComputeRequest;
import com.dvarahq.agentworks.domain.artifact.ArtifactComputeResult;
import com.dvarahq.agentworks.domain.artifact.ArtifactContent;
import com.dvarahq.agentworks.domain.artifact.ArtifactDigest;
import com.dvarahq.agentworks.domain.artifact.ArtifactProvenance;
import com.dvarahq.agentworks.domain.artifact.ArtifactRead;
import com.dvarahq.agentworks.domain.artifact.ArtifactRef;
import com.dvarahq.agentworks.domain.artifact.ArtifactWrite;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

import static com.dvarahq.agentworks.artifact.ArtifactComputeException.Reason.INPUT_LIMIT_EXCEEDED;
import static com.dvarahq.agentworks.artifact.ArtifactComputeException.Reason.INVALID_INPUT;
import static com.dvarahq.agentworks.artifact.ArtifactComputeException.Reason.OUTPUT_LIMIT_EXCEEDED;
import static com.dvarahq.agentworks.artifact.ArtifactComputeException.Reason.TIME_LIMIT_EXCEEDED;
import static com.dvarahq.agentworks.artifact.ArtifactComputeException.Reason.UNAVAILABLE;

/**
 * Initial Artifact Compute implementation. It supports only a closed set of deterministic operators, reads exactly
 * the tenant-scoped references in the request, keeps input/output memory bounded, and publishes output through the
 * ArtifactStore with exact input and run provenance. It never accepts code, filesystem paths, URLs, or credentials.
 */
public final class BoundedArtifactCompute implements ArtifactCompute {
    private static final int BUFFER_SIZE = 8192;

    private final ArtifactStore store;
    private final ArtifactComputeLimits limits;
    private final LongSupplier nanoTime;

    public BoundedArtifactCompute(ArtifactStore store, ArtifactComputeLimits limits) {
        this(store, limits, System::nanoTime);
    }

    BoundedArtifactCompute(ArtifactStore store, ArtifactComputeLimits limits, LongSupplier nanoTime) {
        this.store = Objects.requireNonNull(store, "store");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public ArtifactComputeResult execute(ArtifactComputeRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.inputs().size() > limits.maximumInputs()) {
            throw new ArtifactComputeException(INPUT_LIMIT_EXCEEDED);
        }
        if (request.operator() == ArtifactComputeOperator.SHA256_MANIFEST
                && !"application/json".equals(request.outputMediaType())) {
            throw new ArtifactComputeException(INVALID_INPUT);
        }

        long started = nanoTime.getAsLong();
        List<Input> inputs = new ArrayList<>(request.inputs().size());
        long inputBytes = 0;
        for (ArtifactRef reference : request.inputs()) {
            checkDeadline(started);
            byte[] bytes = read(request, reference, limits.maximumInputBytes() - inputBytes, started);
            inputBytes = addBounded(inputBytes, bytes.length, limits.maximumInputBytes(), INPUT_LIMIT_EXCEEDED);
            inputs.add(new Input(reference, bytes, digest(bytes)));
        }

        byte[] output = switch (request.operator()) {
            case CONCATENATE -> concatenate(inputs);
            case SHA256_MANIFEST -> manifest(inputs, inputBytes);
        };
        if (output.length > limits.maximumOutputBytes()) {
            throw new ArtifactComputeException(OUTPUT_LIMIT_EXCEEDED);
        }
        checkDeadline(started);

        ArtifactWrite write = new ArtifactWrite(request.owner(), request.outputMediaType(), output.length,
                new ArtifactDigest(digest(output)), request.expiresAt(), request.classification(), request.redacted(),
                new ArtifactProvenance("ARTIFACT_COMPUTE", request.actor(), request.run(), request.inputs()),
                new ByteArrayInputStream(output));
        ArtifactRef result = store.put(write);
        // Publication is atomic from the caller's perspective. Never turn a successfully published output into a
        // reported timeout merely because the backend returned after this node-local deadline.
        return new ArtifactComputeResult(result, request.operator(), inputs.size(), inputBytes, output.length);
    }

    private byte[] read(ArtifactComputeRequest request, ArtifactRef reference, long remaining, long started) {
        if (remaining < 0) throw new ArtifactComputeException(INPUT_LIMIT_EXCEEDED);
        try (ArtifactContent content = store.read(ArtifactRead.entire(request.owner(), reference))) {
            if (content.length() > remaining) throw new ArtifactComputeException(INPUT_LIMIT_EXCEEDED);
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(content.length(), BUFFER_SIZE));
            copy(content.stream(), output, remaining, started, INPUT_LIMIT_EXCEEDED);
            return output.toByteArray();
        } catch (ArtifactComputeException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new ArtifactComputeException(UNAVAILABLE, failure);
        }
    }

    private byte[] concatenate(List<Input> inputs) {
        long expected = inputs.stream().mapToLong(input -> input.bytes().length).sum();
        if (expected > limits.maximumOutputBytes()) throw new ArtifactComputeException(OUTPUT_LIMIT_EXCEEDED);
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) expected);
        inputs.forEach(input -> output.writeBytes(input.bytes()));
        return output.toByteArray();
    }

    private byte[] manifest(List<Input> inputs, long totalBytes) {
        StringBuilder json = new StringBuilder("{\"inputs\":[");
        for (int index = 0; index < inputs.size(); index++) {
            Input input = inputs.get(index);
            if (index > 0) json.append(',');
            json.append("{\"ref\":\"").append(input.reference().value())
                    .append("\",\"size\":").append(input.bytes().length)
                    .append(",\"digest\":\"").append(input.digest()).append("\"}");
            if (json.length() > limits.maximumOutputBytes()) {
                throw new ArtifactComputeException(OUTPUT_LIMIT_EXCEEDED);
            }
        }
        json.append("],\"totalBytes\":").append(totalBytes).append('}');
        byte[] result = json.toString().getBytes(StandardCharsets.UTF_8);
        if (result.length > limits.maximumOutputBytes()) throw new ArtifactComputeException(OUTPUT_LIMIT_EXCEEDED);
        return result;
    }

    private void copy(InputStream input, ByteArrayOutputStream output, long maximum, long started,
            ArtifactComputeException.Reason overflow) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long count = 0;
        for (int read; (read = input.read(buffer)) >= 0;) {
            if (read == 0) continue;
            count = addBounded(count, read, maximum, overflow);
            output.write(buffer, 0, read);
            checkDeadline(started);
        }
    }

    private long addBounded(long current, long increment, long maximum, ArtifactComputeException.Reason reason) {
        if (increment > maximum - current) throw new ArtifactComputeException(reason);
        return current + increment;
    }

    private void checkDeadline(long started) {
        long elapsed = nanoTime.getAsLong() - started;
        if (elapsed < 0 || elapsed > limits.timeout().toNanos()) {
            throw new ArtifactComputeException(TIME_LIMIT_EXCEEDED);
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record Input(ArtifactRef reference, byte[] bytes, String digest) {}
}
