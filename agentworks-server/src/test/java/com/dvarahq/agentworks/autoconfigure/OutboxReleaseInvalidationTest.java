package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.controlplane.deployment.ControlPlaneOutboxDispatcher;
import com.dvarahq.agentworks.controlplane.deployment.ControlPlaneOutboxDispatcher.DispatchReport;
import com.dvarahq.agentworks.controlplane.deployment.ControlPlaneOutboxDispatcher.Notification;
import com.dvarahq.agentworks.controlplane.deployment.ControlPlaneOutboxDispatcher.RetryPolicy;
import com.dvarahq.agentworks.runplane.compiled.CompiledReleaseCache;
import com.dvarahq.agentworks.runplane.reconciliation.DeploymentReconciler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Release lifecycle hints retire local compiled material without becoming cache authority (#79). */
class OutboxReleaseInvalidationTest {
    @Test
    @SuppressWarnings("unchecked")
    void restrictiveReleaseEventsInvalidateTheReleaseButResumeDoesNot() {
        var dispatcher = mock(ControlPlaneOutboxDispatcher.class);
        var reconciler = mock(DeploymentReconciler.class);
        var cache = mock(CompiledReleaseCache.class);
        UUID workspace = UUID.randomUUID();
        UUID release = UUID.randomUUID();
        var next = new AtomicReference<>(notification("RELEASE_SUSPENDED", workspace, release));
        when(dispatcher.dispatchRecovering(anyString(), anyInt(), any(), any())).thenAnswer(invocation -> {
            Consumer<Notification> sink = invocation.getArgument(3);
            sink.accept(next.get());
            return new DispatchReport(1, 1, 0, 0);
        });
        var lifecycle = new OutboxReconciliationLifecycle(dispatcher, reconciler, "release-test",
                Duration.ofSeconds(1), 10, RetryPolicy.DEFAULT, null, cache);

        lifecycle.pass();
        next.set(notification("RELEASE_RESUMED", workspace, release));
        lifecycle.pass();

        verify(cache).releaseRevoked(workspace, release);
        verify(reconciler, never()).reconcile(any(), any());
    }

    private static Notification notification(String type, UUID workspace, UUID release) {
        return new Notification(UUID.randomUUID(), 1, type, workspace, null, null, release, Instant.now());
    }
}
