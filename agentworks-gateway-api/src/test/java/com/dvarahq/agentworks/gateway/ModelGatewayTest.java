package com.dvarahq.agentworks.gateway;

import com.dvarahq.agentworks.domain.lifecycle.AgentReleaseId;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.gateway.fake.DeterministicFakeGatewayAdapter;
import com.dvarahq.agentworks.gateway.fake.FakeReply;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Core-side enforcement of the adapter contract, evidence rules, and fail-closed configuration. */
class ModelGatewayTest {

    private static final String SECRET = "sk-live-never-log-me-5512";
    private final WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());
    private final InvocationIdentity identity = new InvocationIdentity(UUID.randomUUID(), workspace.value(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final List<GatewayEvent> events = new ArrayList<>();

    @Test
    void retryAfterHintsAreNormalizedToTheSafeWindow() {
        assertThat(GatewayException.retryable(GatewayErrorCode.PROVIDER_RATE_LIMITED, "retry",
                Duration.ofSeconds(-1)).retryAfter()).isZero();
        assertThat(GatewayException.retryable(GatewayErrorCode.PROVIDER_RATE_LIMITED, "retry",
                Duration.ofHours(1)).retryAfter()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void normalizedCostUsesAnExplicitIsoCurrencyAndImmutableCatalogIdentity() {
        var cost = new ModelUsage.Cost(new java.math.BigDecimal("0.0042"), "USD", "standard", "2026-10");

        assertThat(cost.currency()).isEqualTo("USD");
        assertThatThrownBy(() -> new ModelUsage.Cost(java.math.BigDecimal.ONE, "usd", "standard", "2026-10"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retriesOnlyAnExplicitlySafeOutcomeAndEventsEveryAttempt() {
        AtomicInteger calls = new AtomicInteger();
        AiGatewayAdapter flaky = adapter((invocation, sink) -> {
            sink.requestSent();
            if (calls.incrementAndGet() == 1) {
                throw GatewayException.retryable(GatewayErrorCode.PROVIDER_RATE_LIMITED, "try later", Duration.ZERO);
            }
            return result("recovered");
        });
        GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(flaky, CapabilityPolicy.DEFAULT,
                null)));
        PinnedModel base = pin(registry);
        PinnedModel resilient = new PinnedModel(base.workspace(), base.release(), base.identity(), policy(2, List.of()));

        ModelResponse response = new ModelGateway(registry, null, null, events::add, Clock.systemUTC())
                .call(invocation(resilient));

        assertThat(response.content()).isEqualTo("recovered");
        assertThat(calls).hasValue(2);
        assertThat(events).filteredOn(GatewayEvent.ModelInvocationAttempted.class::isInstance).hasSize(2);
        assertThat(events).filteredOn(GatewayEvent.ModelInvocationFailed.class::isInstance).hasSize(1);
        assertThat(events).filteredOn(GatewayEvent.ModelInvocationCompleted.class::isInstance).hasSize(1);
    }

    @Test
    void ambiguousAndPartialResultsAreNeverRepeated() {
        AtomicInteger ambiguousCalls = new AtomicInteger();
        AiGatewayAdapter ambiguous = adapter((invocation, sink) -> {
            sink.requestSent();
            ambiguousCalls.incrementAndGet();
            throw new GatewayException(GatewayErrorCode.TIMEOUT, "ambiguous timeout");
        });
        GatewayRegistry ambiguousRegistry = GatewayRegistry.of(List.of(new ConfiguredGateway(ambiguous,
                CapabilityPolicy.DEFAULT, null)));
        PinnedModel ambiguousBase = pin(ambiguousRegistry);
        PinnedModel ambiguousPin = new PinnedModel(ambiguousBase.workspace(), ambiguousBase.release(),
                ambiguousBase.identity(), policy(3, List.of()));

        assertThatThrownBy(() -> new ModelGateway(ambiguousRegistry, null, null, ignored -> { }, Clock.systemUTC())
                .call(invocation(ambiguousPin))).isInstanceOfSatisfying(GatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo(GatewayErrorCode.TIMEOUT));
        assertThat(ambiguousCalls).hasValue(1);

        AtomicInteger partialCalls = new AtomicInteger();
        AiGatewayAdapter partial = adapter((invocation, sink) -> {
            sink.requestSent();
            partialCalls.incrementAndGet();
            sink.emit("already visible");
            throw GatewayException.retryable(GatewayErrorCode.PROVIDER_UNAVAILABLE, "disconnected", Duration.ZERO);
        });
        GatewayRegistry partialRegistry = GatewayRegistry.of(List.of(new ConfiguredGateway(partial,
                CapabilityPolicy.DEFAULT, null)));
        PinnedModel partialBase = pin(partialRegistry);
        PinnedModel partialPin = new PinnedModel(partialBase.workspace(), partialBase.release(), partialBase.identity(),
                policy(3, List.of()));

        assertThatThrownBy(() -> new ModelGateway(partialRegistry, null, null, events::add, Clock.systemUTC())
                .stream(invocation(partialPin), ignored -> { })).isInstanceOfSatisfying(GatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo(GatewayErrorCode.PARTIAL_STREAM));
        assertThat(partialCalls).hasValue(1);
        assertThat(events).last().isInstanceOfSatisfying(GatewayEvent.ModelInvocationFailed.class,
                failure -> assertThat(failure.usage().status()).isEqualTo(ModelUsage.UsageStatus.ESTIMATED));
    }

    @Test
    void fallbackUsesOnlyAConcreteCompatibleIdentityCapturedInPolicy() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AiGatewayAdapter primary = adapter("primary", "primary-model", (invocation, sink) -> {
            primaryCalls.incrementAndGet();
            throw GatewayException.notSent(GatewayErrorCode.PROVIDER_UNAVAILABLE, "connection refused");
        });
        var backup = DeterministicFakeGatewayAdapter.builder("backup").model("m", "backup-model").build();
        GatewayRegistry registry = GatewayRegistry.of(List.of(
                new ConfiguredGateway(primary, CapabilityPolicy.DEFAULT, null),
                new ConfiguredGateway(backup, CapabilityPolicy.DEFAULT, null)));
        ResolvedModelIdentity primaryIdentity = registry.resolve(List.of(
                new ModelRoleBinding(ModelRole.REASONING, "primary", "m"))).get(ModelRole.REASONING);
        ResolvedModelIdentity backupIdentity = registry.resolve(List.of(
                new ModelRoleBinding(ModelRole.REASONING, "backup", "m"))).get(ModelRole.REASONING);
        PinnedModel pin = new PinnedModel(workspace, new AgentReleaseId(UUID.randomUUID()), primaryIdentity,
                policy(1, List.of(backupIdentity)));

        ModelResponse response = new ModelGateway(registry, null, null, events::add, Clock.systemUTC())
                .call(invocation(pin));

        assertThat(primaryCalls).hasValue(1);
        assertThat(backup.requests()).isEqualTo(1);
        assertThat(response.pinned()).isEqualTo(backupIdentity);
        assertThat(events).filteredOn(GatewayEvent.ModelFallbackSelected.class::isInstance).singleElement();
    }

    @Test
    void breakerOpensForOneReleaseRouteAndPublishesReadiness() {
        AtomicInteger calls = new AtomicInteger();
        AiGatewayAdapter unavailable = adapter((invocation, sink) -> {
            calls.incrementAndGet();
            throw GatewayException.notSent(GatewayErrorCode.PROVIDER_UNAVAILABLE, "connection refused");
        });
        GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(unavailable,
                CapabilityPolicy.DEFAULT, null)));
        PinnedModel base = pin(registry);
        ModelResiliencePolicy policy = new ModelResiliencePolicy(1, Duration.ZERO, Duration.ZERO, 0, 2,
                Duration.ZERO, 2, Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(1), List.of());
        PinnedModel protectedRoute = new PinnedModel(base.workspace(), base.release(), base.identity(), policy);
        ModelGateway gateway = new ModelGateway(registry, null, null, events::add, Clock.systemUTC());

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> gateway.call(invocation(protectedRoute)))
                    .isInstanceOfSatisfying(GatewayException.class,
                            failure -> assertThat(failure.code()).isEqualTo(GatewayErrorCode.PROVIDER_UNAVAILABLE));
        }
        assertThatThrownBy(() -> gateway.call(invocation(protectedRoute)))
                .isInstanceOfSatisfying(GatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo(GatewayErrorCode.MODEL_ROUTE_OPEN));

        assertThat(calls).hasValue(2);
        assertThat(gateway.health()).singleElement().satisfies(health -> {
            assertThat(health.breakerState()).isEqualTo("OPEN");
            assertThat(health.failures()).isEqualTo(2);
            assertThat(health.capacity()).isEqualTo(2);
        });
        assertThat(events).filteredOn(GatewayEvent.ModelRouteStateChanged.class::isInstance).singleElement()
                .extracting(event -> ((GatewayEvent.ModelRouteStateChanged) event).state()).isEqualTo("OPEN");
    }

    @Test
    void expiredBreakerAdmitsOneProbeAndClosesAfterRecovery() {
        AtomicInteger calls = new AtomicInteger();
        AiGatewayAdapter recovering = adapter((invocation, sink) -> {
            if (calls.incrementAndGet() == 1) {
                throw GatewayException.notSent(GatewayErrorCode.PROVIDER_UNAVAILABLE, "connection refused");
            }
            return result("recovered");
        });
        GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(recovering,
                CapabilityPolicy.DEFAULT, null)));
        PinnedModel base = pin(registry);
        ModelResiliencePolicy policy = new ModelResiliencePolicy(1, Duration.ZERO, Duration.ZERO, 0, 2,
                Duration.ZERO, 1, Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(1), List.of());
        PinnedModel protectedRoute = new PinnedModel(base.workspace(), base.release(), base.identity(), policy);
        MutableClock clock = new MutableClock();
        ModelGateway gateway = new ModelGateway(registry, null, null, events::add, clock);

        assertThatThrownBy(() -> gateway.call(invocation(protectedRoute)))
                .isInstanceOfSatisfying(GatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo(GatewayErrorCode.PROVIDER_UNAVAILABLE));
        clock.advance(Duration.ofSeconds(31));

        assertThat(gateway.call(invocation(protectedRoute)).content()).isEqualTo("recovered");
        assertThat(gateway.health()).singleElement()
                .extracting(ModelGateway.ModelRouteHealth::breakerState).isEqualTo("CLOSED");
        assertThat(events).filteredOn(GatewayEvent.ModelRouteStateChanged.class::isInstance)
                .extracting(event -> ((GatewayEvent.ModelRouteStateChanged) event).state())
                .containsExactly("OPEN", "HALF_OPEN", "CLOSED");
    }

    @Test
    void saturatedBulkheadRejectsOnlyTheSameReleaseRoute() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        AiGatewayAdapter blocking = adapter((invocation, sink) -> {
            entered.countDown();
            try {
                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new AssertionError("test did not release provider");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new GatewayException(GatewayErrorCode.CANCELLED, "interrupted");
            }
            return result("done");
        });
        GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(blocking,
                CapabilityPolicy.DEFAULT, null)));
        PinnedModel base = pin(registry);
        ModelResiliencePolicy policy = new ModelResiliencePolicy(1, Duration.ZERO, Duration.ZERO, 0, 1,
                Duration.ZERO, 3, Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(1), List.of());
        PinnedModel firstRelease = new PinnedModel(base.workspace(), base.release(), base.identity(), policy);
        PinnedModel otherRelease = new PinnedModel(base.workspace(), new AgentReleaseId(UUID.randomUUID()),
                base.identity(), policy);
        ModelGateway gateway = new ModelGateway(registry, null, null, events::add, Clock.systemUTC());
        var firstFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread first = Thread.ofPlatform().start(() -> {
            try {
                gateway.call(invocation(firstRelease));
            } catch (Throwable failure) {
                firstFailure.set(failure);
            }
        });
        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> gateway.call(invocation(firstRelease)))
                .isInstanceOfSatisfying(GatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo(GatewayErrorCode.MODEL_CAPACITY_EXHAUSTED));
        release.countDown();
        first.join(Duration.ofSeconds(5));
        assertThat(firstFailure.get()).isNull();
        assertThat(gateway.call(invocation(otherRelease)).content()).isEqualTo("done");
    }

    @Test
    void releasePinnedStreamIdleTimeoutCancelsBeforeTheOverallDeadline() {
        var slow = DeterministicFakeGatewayAdapter.builder("direct").model("m", "provider-m")
                .script(invocation -> FakeReply.text(List.of("late"), null)
                        .withChunkDelay(Duration.ofMillis(250))).build();
        GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(slow, CapabilityPolicy.DEFAULT,
                null)));
        PinnedModel base = pin(registry);
        ModelResiliencePolicy policy = new ModelResiliencePolicy(1, Duration.ZERO, Duration.ZERO, 0, 2,
                Duration.ZERO, 3, Duration.ofSeconds(30), Duration.ofSeconds(4), Duration.ofMillis(30), List.of());
        PinnedModel bounded = new PinnedModel(base.workspace(), base.release(), base.identity(), policy);
        long started = System.nanoTime();

        assertThatThrownBy(() -> new ModelGateway(registry, null, null, events::add, Clock.systemUTC())
                .stream(invocation(bounded), ignored -> { })).isInstanceOfSatisfying(GatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo(GatewayErrorCode.TIMEOUT));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void delayedUsageReconciliationReportsDiscrepancyWithoutAnotherProviderCall() {
        var fake = DeterministicFakeGatewayAdapter.builder("direct").model("m", "provider-m").build();
        GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(fake,
                CapabilityPolicy.DEFAULT, null)));
        PinnedModel model = pin(registry);
        ModelInvocation invocation = invocation(model);
        ModelGateway gateway = new ModelGateway(registry, null, null, events::add, Clock.systemUTC());
        ModelUsage estimated = ModelUsage.estimated(10, 4, 2);
        ModelUsage reported = new ModelUsage(ModelUsage.UsageStatus.REPORTED, 12L, 3L, 16L,
                1L, 0L, 1L, null);

        ModelUsage.Reconciliation reconciliation = gateway.reconcileUsage(invocation, model.identity(), estimated,
                reported);

        assertThat(reconciliation.usage().status()).isEqualTo(ModelUsage.UsageStatus.RECONCILED);
        assertThat(reconciliation.usage().totalTokens()).isEqualTo(16);
        assertThat(reconciliation.discrepancy().inputTokens()).isEqualTo(2);
        assertThat(reconciliation.discrepancy().outputTokens()).isEqualTo(-1);
        assertThat(reconciliation.discrepancy().totalTokens()).isZero();
        assertThat(fake.requests()).isZero();
        assertThat(events).last().isInstanceOf(GatewayEvent.ModelUsageReconciled.class);
    }

    private static final class MutableClock extends Clock {
        private java.time.Instant now = java.time.Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public java.time.Instant instant() { return now; }
    }

    @Test
    void adapterContractViolationsAreTypedFailures() {
        assertThat(failureOf(adapter((invocation, sink) -> {
            sink.emit("streamed without permission");
            return result("streamed without permission");
        }), false).code()).isEqualTo(GatewayErrorCode.ADAPTER_FAILURE);
        assertThat(failureOf(adapter((invocation, sink) -> new AdapterResult("", List.of(new ToolCall("c", "t", "{}")),
                FinishReason.TOOL_CALLS, null, null, null)), false).code()).isEqualTo(GatewayErrorCode.ADAPTER_FAILURE);
        assertThat(failureOf(adapter((invocation, sink) -> {
            sink.emit("abc");
            return result("abcdef");
        }), true).code()).isEqualTo(GatewayErrorCode.ADAPTER_FAILURE);
        assertThat(failureOf(adapter((invocation, sink) -> null), false).code())
                .isEqualTo(GatewayErrorCode.ADAPTER_FAILURE);
    }

    @Test
    void invalidProviderMetadataAndBrokenAdaptersFailTypedWithEvidence() {
        var badServedModel = DeterministicFakeGatewayAdapter.builder("direct").model("m", "provider-m")
                .script(invocation -> FakeReply.text(List.of("done"), null).servedBy(" ", null)).build();
        GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(badServedModel,
                CapabilityPolicy.DEFAULT, null)));
        var invalid = catchThrowableOfType(GatewayException.class,
                () -> new ModelGateway(registry, null, null, events::add, Clock.systemUTC()).call(invocation(pin(registry))));
        AiGatewayAdapter broken = new AiGatewayAdapter() {
            private final AiGatewayAdapter identity = adapter((invocation, sink) -> result("x"));

            @Override
            public GatewayDescriptor descriptor() {
                return identity.descriptor();
            }

            @Override
            public Set<GatewayCapability> capabilities() {
                return null;
            }

            @Override
            public ResolvedModel resolveModel(ModelResolutionRequest request) {
                return identity.resolveModel(request);
            }

            @Override
            public AdapterResult invoke(AdapterInvocation invocation, ChunkSink sink) {
                throw new AssertionError("never invoked");
            }
        };
        ModelGateway brokenGateway = new ModelGateway(GatewayRegistry.of(List.of(new ConfiguredGateway(broken,
                CapabilityPolicy.DEFAULT, null))), null, null, events::add, Clock.systemUTC());
        var noCapabilities = catchThrowableOfType(GatewayException.class, () -> brokenGateway.call(invocation(pin())));

        assertThat(invalid.code()).isEqualTo(GatewayErrorCode.INVALID_PROVIDER_RESPONSE);
        assertThat(noCapabilities.code()).isEqualTo(GatewayErrorCode.ADAPTER_FAILURE);
        assertThat(events).filteredOn(GatewayEvent.ModelInvocationFailed.class::isInstance).hasSize(2);
    }

    // Review finding 2: an interrupted caller still waits (bounded) for an in-progress listener callback.
    @Test
    void anInterruptedCallerStillWaitsForTheInProgressListenerCallback() throws Exception {
        var fake = DeterministicFakeGatewayAdapter.builder("direct").model("m", "provider-m")
                .script(invocation -> FakeReply.text(List.of("a", "b", "c"), null)).build();
        GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(fake, CapabilityPolicy.DEFAULT, null)));
        ModelGateway gateway = new ModelGateway(registry, null, null, events::add, Clock.systemUTC());
        var entered = new java.util.concurrent.CountDownLatch(1);
        var listenerFinished = new java.util.concurrent.atomic.AtomicBoolean();
        var finishedWhenCallerReturned = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        var outcome = new java.util.concurrent.atomic.AtomicReference<GatewayErrorCode>();
        PinnedModel pinned = pin(registry);

        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                gateway.stream(invocation(pinned), chunk -> {
                    entered.countDown();
                    long until = System.nanoTime() + java.time.Duration.ofMillis(300).toNanos();
                    while (System.nanoTime() < until) {
                        Thread.onSpinWait();
                    }
                    listenerFinished.set(true);
                });
            } catch (GatewayException stopped) {
                outcome.set(stopped.code());
            } finally {
                finishedWhenCallerReturned.set(listenerFinished.get());
            }
        });
        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(10_000);

        assertThat(outcome.get()).isEqualTo(GatewayErrorCode.CANCELLED);
        assertThat(finishedWhenCallerReturned.get()).isTrue();
    }

    // Review finding 7: an Error from an adapter leaves failure evidence; only VM errors escape as themselves.
    @Test
    void adapterErrorsLeaveFailureEvidenceAndOnlyVirtualMachineErrorsEscape() {
        OutOfMemoryError simulated = new OutOfMemoryError("simulated");

        GatewayException linkage = failureOf(adapter((invocation, sink) -> {
            throw new NoClassDefFoundError("missing/Type");
        }), false);
        long afterLinkage = events.stream().filter(GatewayEvent.ModelInvocationFailed.class::isInstance).count();
        GatewayRegistry vmError = GatewayRegistry.of(List.of(new ConfiguredGateway(adapter((invocation, sink) -> {
            throw simulated;
        }), CapabilityPolicy.DEFAULT, null)));
        ModelGateway gateway = new ModelGateway(vmError, null, null, events::add, Clock.systemUTC());
        assertThatThrownBy(() -> gateway.call(invocation(pin(vmError)))).isSameAs(simulated);

        assertThat(linkage.code()).isEqualTo(GatewayErrorCode.ADAPTER_FAILURE);
        assertThat(linkage.getCause()).isNull();
        assertThat(afterLinkage).isEqualTo(1);
        assertThat(events).filteredOn(GatewayEvent.ModelInvocationFailed.class::isInstance).hasSize(2).allSatisfy(event -> assertThat(event)
                .isInstanceOfSatisfying(GatewayEvent.ModelInvocationFailed.class,
                        failed -> assertThat(failed.code()).isEqualTo(GatewayErrorCode.ADAPTER_FAILURE)));
    }

    // Review 2, finding 1: usage that is absent entirely follows the captured usage policy.
    @Test
    void missingUsageFollowsTheCapturedUsagePolicy() {
        var noUsage = DeterministicFakeGatewayAdapter.builder("direct").model("m", "provider-m")
                .script(invocation -> FakeReply.text(List.of("done"), null)).build();
        GatewayRegistry degradable = GatewayRegistry.of(List.of(new ConfiguredGateway(noUsage, CapabilityPolicy.DEFAULT,
                null)));
        GatewayRegistry required = GatewayRegistry.of(List.of(new ConfiguredGateway(noUsage,
                CapabilityPolicy.withOverrides(java.util.Map.of(GatewayCapability.USAGE, CapabilityMode.REQUIRED)), null)));

        ModelResponse degraded = new ModelGateway(degradable, null, null, events::add, Clock.systemUTC())
                .call(invocation(pin(degradable)));
        var refused = catchThrowableOfType(GatewayException.class,
                () -> new ModelGateway(required, null, null, events::add, Clock.systemUTC()).call(invocation(pin(required))));

        assertThat(degraded.usage().status()).isEqualTo(ModelUsage.UsageStatus.ESTIMATED);
        assertThat(degraded.usage().totalTokens()).isPositive();
        assertThat(degraded.degradations()).containsExactly(DegradedBehavior.USAGE_UNREPORTED);
        assertThat(events).filteredOn(GatewayEvent.CapabilityDegraded.class::isInstance).hasSize(1);
        assertThat(refused.code()).isEqualTo(GatewayErrorCode.INVALID_PROVIDER_RESPONSE);
    }

    // Review 2, finding 6: a GatewayException thrown by the listener is the caller's own failure.
    @Test
    void aGatewayExceptionFromTheListenerIsRecordedAsCancelled() {
        var fake = DeterministicFakeGatewayAdapter.builder("direct").model("m", "provider-m")
                .script(invocation -> FakeReply.text(List.of("a", "b"), null)).build();
        GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(fake, CapabilityPolicy.DEFAULT, null)));
        GatewayException fromListener = new GatewayException(GatewayErrorCode.PROVIDER_UNAVAILABLE, "listener's own");

        assertThatThrownBy(() -> new ModelGateway(registry, null, null, events::add, Clock.systemUTC())
                .stream(invocation(pin(registry)), chunk -> {
                    throw fromListener;
                })).isSameAs(fromListener);
        assertThat(events).last().isInstanceOfSatisfying(GatewayEvent.ModelInvocationFailed.class,
                failed -> assertThat(failed.code()).isEqualTo(GatewayErrorCode.CANCELLED));
    }

    // Review 2, finding 8: evidence follows whether the request left, not the failure code.
    @Test
    void aFailureAfterTheRequestWasSentIsReportedAsContacted() {
        GatewayException failure = failureOf(adapter((invocation, sink) -> {
            sink.requestSent();
            throw new GatewayException(GatewayErrorCode.CREDENTIAL_UNAVAILABLE, "expired mid-invocation");
        }), false);

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.CREDENTIAL_UNAVAILABLE);
        assertThat(events).last().isInstanceOfSatisfying(GatewayEvent.ModelInvocationFailed.class,
                failed -> assertThat(failed.providerContacted()).isTrue());
    }

    // Review 3, finding 3: a credential failure scripted after sending is reported from the send state.
    @Test
    void aCredentialFailureAfterSendingIsScriptableAndReportedAsContacted() {
        var fake = DeterministicFakeGatewayAdapter.builder("direct").model("m", "provider-m")
                .script(invocation -> FakeReply.failing(GatewayErrorCode.CREDENTIAL_UNAVAILABLE)).build();
        GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(fake, CapabilityPolicy.DEFAULT, null)));

        var failure = catchThrowableOfType(GatewayException.class,
                () -> new ModelGateway(registry, null, null, events::add, Clock.systemUTC()).call(invocation(pin(registry))));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.CREDENTIAL_UNAVAILABLE);
        assertThat(GatewayErrorCode.CREDENTIAL_UNAVAILABLE.beforeProviderContact()).isFalse();
        assertThat(events).last().isInstanceOfSatisfying(GatewayEvent.ModelInvocationFailed.class,
                failed -> assertThat(failed.providerContacted()).isTrue());
    }

    // Review 4, item 3: a failed SUCCEEDED audit record fails closed; the material is never published or used.
    @Test
    void aFailingSuccessAuditFailsClosedBeforeTheRequestIsSent() {
        var fake = DeterministicFakeGatewayAdapter.builder("direct").model("m", "provider-m").requireCredential(true)
                .build();
        GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(fake, CapabilityPolicy.DEFAULT,
                new CredentialRef("key"))));
        var issued = new java.util.concurrent.CopyOnWriteArrayList<com.dvarahq.agentworks.credential.ResolvedCredential>();
        com.dvarahq.agentworks.credential.CredentialProvider provider =
                new com.dvarahq.agentworks.credential.CredentialProvider() {
                    @Override
                    public com.dvarahq.agentworks.credential.CredentialAuthorization authorize(CredentialRef reference,
                            InvocationIdentity who, java.time.Instant now) {
                        return new com.dvarahq.agentworks.credential.CredentialAuthorization(UUID.randomUUID(),
                                reference, who);
                    }

                    @Override
                    public com.dvarahq.agentworks.credential.ResolvedCredential resolve(
                            com.dvarahq.agentworks.credential.CredentialAuthorization authorization, java.time.Instant now) {
                        var material = new com.dvarahq.agentworks.credential.ResolvedCredential(SECRET.toCharArray(),
                                now.plusSeconds(60));
                        issued.add(material);
                        return material;
                    }
                };
        ModelGateway gateway = new ModelGateway(registry, provider, audit -> {
            if (audit.outcome().equals("SUCCEEDED")) {
                throw new IllegalStateException("audit store unavailable");
            }
        }, events::add, Clock.systemUTC());

        var failure = catchThrowableOfType(GatewayException.class, () -> gateway.call(invocation(pin(registry))));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.CREDENTIAL_UNAVAILABLE);
        assertThat(fake.requests()).isZero();
        assertThat(fake.lastCredentialDigest()).isNull();
        assertThat(issued).singleElement().satisfies(material -> assertThatThrownBy(
                () -> material.use(java.time.Instant.now(), chars -> chars)).isInstanceOf(IllegalStateException.class));
        assertThat(events).last().isInstanceOfSatisfying(GatewayEvent.ModelInvocationFailed.class,
                failed -> assertThat(failed.providerContacted()).isFalse());
    }

    @Test
    void anAdapterExceptionCauseIsNotPropagated() {
        GatewayException failure = failureOf(adapter((invocation, sink) -> {
            GatewayException withCause = new GatewayException(GatewayErrorCode.PROVIDER_UNAVAILABLE, "down");
            withCause.initCause(new IllegalStateException(SECRET));
            throw withCause;
        }), false);

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.PROVIDER_UNAVAILABLE);
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void unexpectedAdapterExceptionsNeverPropagateTheirMessagesOrCauses() {
        GatewayException failure = failureOf(adapter((invocation, sink) -> {
            throw new IllegalStateException("provider said: invalid key " + SECRET);
        }), false);

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.ADAPTER_FAILURE);
        assertThat(failure.getMessage()).doesNotContain(SECRET).contains("IllegalStateException");
        assertThat(failure.getCause()).isNull();
        assertThat(events.toString()).doesNotContain(SECRET);
    }

    @Test
    void aMissingOrRetypedGatewayIsRefusedBeforeInvocation() {
        PinnedModel pinned = pin();
        ModelGateway empty = new ModelGateway(GatewayRegistry.empty(), null, null, events::add, Clock.systemUTC());

        assertThat(catchThrowableOfType(GatewayException.class, () -> empty.call(invocation(pinned))).code())
                .isEqualTo(GatewayErrorCode.GATEWAY_NOT_CONFIGURED);
        assertThat(events).filteredOn(GatewayEvent.ModelInvocationFailed.class::isInstance).singleElement()
                .isInstanceOfSatisfying(GatewayEvent.ModelInvocationFailed.class,
                failed -> assertThat(failed.providerContacted()).isFalse());
    }

    @Test
    void aCredentialReferenceWithoutACredentialAuthorityFailsClosed() {
        var fake = DeterministicFakeGatewayAdapter.builder("direct").model("m", "provider-m").build();
        GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(fake, CapabilityPolicy.DEFAULT,
                new CredentialRef("prod-key"))));
        ModelGateway gateway = new ModelGateway(registry, null, null, events::add, Clock.systemUTC());

        var failure = catchThrowableOfType(GatewayException.class, () -> gateway.call(invocation(pin(registry))));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.CREDENTIAL_UNAVAILABLE);
        assertThat(fake.requests()).isZero();
    }

    @Test
    void aGatewayWithoutACredentialNeverResolvesOne() {
        var fake = DeterministicFakeGatewayAdapter.builder("direct").model("m", "provider-m").build();
        GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(fake, CapabilityPolicy.DEFAULT, null)));

        ModelResponse response = new ModelGateway(registry, null, null, events::add, Clock.systemUTC())
                .call(invocation(pin(registry)));

        assertThat(response.content()).isEqualTo("echo(provider-m): hello there");
        assertThat(response.usage().outputTokens()).isEqualTo(3);
        assertThat(fake.lastCredentialDigest()).isNull();
    }

    @Test
    void aFailingDegradationSinkAbortsBeforeProviderContact() {
        var fake = DeterministicFakeGatewayAdapter.builder("direct").model("m", "provider-m")
                .capabilities(Set.of(GatewayCapability.TOOL_CALLS)).build();
        GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(fake, CapabilityPolicy.DEFAULT, null)));
        IllegalStateException sinkDown = new IllegalStateException("event store unavailable");
        ModelGateway gateway = new ModelGateway(registry, null, null, event -> {
            throw sinkDown;
        }, Clock.systemUTC());

        assertThatThrownBy(() -> gateway.call(invocation(pin(registry)))).isSameAs(sinkDown);
        assertThat(fake.requests()).isZero();
    }

    @Test
    void aFailingCompletionSinkDoesNotTurnACompletedTurnIntoAFailure() {
        var fake = DeterministicFakeGatewayAdapter.builder("direct").model("m", "provider-m").build();
        GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(fake, CapabilityPolicy.DEFAULT, null)));
        ModelGateway gateway = new ModelGateway(registry, null, null, event -> {
            throw new IllegalStateException("event store unavailable");
        }, Clock.systemUTC());

        assertThat(gateway.call(invocation(pin(registry))).content()).startsWith("echo(");
        assertThat(fake.requests()).isEqualTo(1);
    }

    @Test
    void requestsAndResponsesNeverRenderContentInToString() {
        var fake = DeterministicFakeGatewayAdapter.builder("direct").model("m", "provider-m")
                .script(invocation -> FakeReply.text(List.of(SECRET), null)).build();
        GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(fake, CapabilityPolicy.DEFAULT, null)));
        ModelRequest request = ModelRequest.of(ModelMessage.user(SECRET));

        ModelResponse response = new ModelGateway(registry, null, null, events::add, Clock.systemUTC())
                .call(new ModelInvocation(pin(registry), identity, new InvocationCorrelation(null, "t"), request,
                        Duration.ofSeconds(5), null));

        assertThat(response.content()).isEqualTo(SECRET);
        assertThat(response + " " + request + " " + request.messages() + " " + new ModelChunk(0, SECRET) + " "
                + new ToolCall("c", "t", SECRET) + " " + events).doesNotContain(SECRET);
    }

    @Test
    void invocationsValidateTheirDeadline() {
        PinnedModel pinned = pin();
        ModelRequest request = ModelRequest.of(ModelMessage.user("x"));
        InvocationCorrelation correlation = new InvocationCorrelation(null, "t");

        assertThatThrownBy(() -> new ModelInvocation(pinned, identity, correlation, request, Duration.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelInvocation(pinned, identity, correlation, request, Duration.ofHours(2), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private GatewayException failureOf(AiGatewayAdapter adapter, boolean streaming) {
        GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(adapter, CapabilityPolicy.DEFAULT,
                null)));
        ModelGateway gateway = new ModelGateway(registry, null, null, events::add, Clock.systemUTC());
        return catchThrowableOfType(GatewayException.class, () -> {
            if (streaming) {
                gateway.stream(invocation(pin(registry)), chunk -> { });
            } else {
                gateway.call(invocation(pin(registry)));
            }
        });
    }

    private PinnedModel pin() {
        return pin(GatewayRegistry.of(List.of(new ConfiguredGateway(
                DeterministicFakeGatewayAdapter.builder("direct").model("m", "provider-m").build(),
                CapabilityPolicy.DEFAULT, null))));
    }

    private PinnedModel pin(GatewayRegistry registry) {
        var identities = registry.resolve(List.of(new ModelRoleBinding(ModelRole.REASONING, "direct", "m")));
        return PinnedModelRoles.of(workspace, new AgentReleaseId(UUID.randomUUID()), identities)
                .role(ModelRole.REASONING);
    }

    private ModelInvocation invocation(PinnedModel pinned) {
        return new ModelInvocation(pinned, identity, new InvocationCorrelation(null, "trace"),
                ModelRequest.of(ModelMessage.user("hello there")), Duration.ofSeconds(5), null);
    }

    private static AdapterResult result(String content) {
        return new AdapterResult(content, List.of(), FinishReason.STOP, null, null, null);
    }

    /** A custom-behavior adapter with the same identity as the fake, so pins resolve against it. */
    private static AiGatewayAdapter adapter(BiFunction<AdapterInvocation, ChunkSink, AdapterResult> behavior) {
        return adapter("direct", "provider-m", behavior);
    }

    private static AiGatewayAdapter adapter(String gatewayId, String providerModel,
            BiFunction<AdapterInvocation, ChunkSink, AdapterResult> behavior) {
        var identity = DeterministicFakeGatewayAdapter.builder(gatewayId).model("m", providerModel).build();
        return new AiGatewayAdapter() {
            @Override
            public GatewayDescriptor descriptor() {
                return identity.descriptor();
            }

            @Override
            public Set<GatewayCapability> capabilities() {
                return identity.capabilities();
            }

            @Override
            public ResolvedModel resolveModel(ModelResolutionRequest request) {
                return identity.resolveModel(request);
            }

            @Override
            public AdapterResult invoke(AdapterInvocation invocation, ChunkSink sink) {
                return behavior.apply(invocation, sink);
            }
        };
    }

    private static ModelResiliencePolicy policy(int attempts, List<ResolvedModelIdentity> fallbacks) {
        return new ModelResiliencePolicy(attempts, Duration.ZERO, Duration.ZERO, 0, 4, Duration.ZERO, 3,
                Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(1), fallbacks);
    }
}
