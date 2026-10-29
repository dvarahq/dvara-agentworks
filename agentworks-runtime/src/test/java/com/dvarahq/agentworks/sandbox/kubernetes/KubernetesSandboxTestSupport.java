package com.dvarahq.agentworks.sandbox.kubernetes;

import com.dvarahq.agentworks.domain.sandbox.SandboxConfiguration;
import com.dvarahq.agentworks.domain.sandbox.SandboxRef;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class KubernetesSandboxTestSupport {
    static final String IMAGE = "registry.example/sandbox@sha256:"
            + "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

    static SandboxConfiguration configuration() {
        return new SandboxConfiguration("kubernetes", "demand", Optional.of(IMAGE), new BigDecimal("0.5"),
                "64Mi", 32, "NONE", "5m");
    }

    static final class FakeControl implements KubernetesSandboxProvider.Control {
        final Map<UUID, SandboxRef> resources = new LinkedHashMap<>();
        SandboxRef lastCreated;
        SandboxConfiguration lastConfiguration;
        RuntimeException createFailure;
        RuntimeException executionFailure;
        RuntimeException removeFailure;

        @Override
        public synchronized void create(SandboxRef reference, SandboxConfiguration configuration) {
            if (createFailure != null) throw createFailure;
            if (resources.putIfAbsent(reference.value(), reference) != null) {
                throw new KubernetesSandboxException("duplicate");
            }
            lastCreated = reference;
            lastConfiguration = configuration;
        }

        @Override
        public String execute(UUID sandboxId, List<String> command, Duration timeout) throws InterruptedException {
            if (executionFailure != null) throw executionFailure;
            if (command.contains("long")) {
                long deadline = System.nanoTime() + timeout.toNanos();
                while (present(sandboxId) && System.nanoTime() < deadline) Thread.sleep(5);
                throw new KubernetesSandboxTimeoutException("timed out or removed");
            }
            return String.join(" ", command);
        }

        @Override
        public synchronized Set<SandboxRef> discover() {
            return Set.copyOf(resources.values());
        }

        @Override
        public synchronized Optional<SandboxRef> inspect(UUID sandboxId) {
            return Optional.ofNullable(resources.get(sandboxId));
        }

        @Override
        public synchronized void remove(SandboxRef expected) {
            if (removeFailure != null) throw removeFailure;
            SandboxRef actual = resources.get(expected.value());
            if (actual == null) return;
            if (!actual.equals(expected)) throw new KubernetesSandboxException("ownership mismatch");
            resources.remove(expected.value());
        }

        synchronized boolean present(UUID id) {
            return resources.containsKey(id);
        }
    }

    private KubernetesSandboxTestSupport() {}
}
