package com.dvarahq.agentworks.gateway;

import com.dvarahq.agentworks.credential.CredentialAuditEvent;
import com.dvarahq.agentworks.credential.CredentialAuthorization;
import com.dvarahq.agentworks.credential.CredentialProvider;
import com.dvarahq.agentworks.credential.ResolvedCredential;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Just-in-time credential resolution within one invocation (review finding 8). */
class JitGatewayCredentialTest {

    // Review 2, finding 2: a slow audit sink never holds up close() (and so the invocation deadline).
    @Test
    void closeNeverWaitsForASlowAuditSink() throws Exception {
        CountDownLatch auditing = new CountDownLatch(1);
        CredentialProvider provider = new CredentialProvider() {
            @Override
            public CredentialAuthorization authorize(CredentialRef reference, InvocationIdentity identity, Instant now) {
                return new CredentialAuthorization(UUID.randomUUID(), reference, identity);
            }

            @Override
            public ResolvedCredential resolve(CredentialAuthorization authorization, Instant now) {
                return new ResolvedCredential("material".toCharArray(), now.plusSeconds(60));
            }
        };
        InvocationIdentity identity = new InvocationIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        JitGatewayCredential credential = new JitGatewayCredential("direct", new CredentialRef("key"), provider, event -> {
            auditing.countDown();
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, identity, Clock.systemUTC(), CancellationSignal.create());
        Thread.ofVirtual().start(() -> {
            try {
                credential.withMaterial(String::valueOf);
            } catch (GatewayException ended) {
                // Expected once close() has run.
            }
        });
        assertThat(auditing.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        credential.close();

        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofMillis(500));
    }

    // Review 3, finding 5: the audit record precedes publication, and a close() during it is recorded truthfully.
    @Test
    void aCloseDuringTheSuccessAuditIsRecordedAsAbandonedAndTheMaterialIsNeverUsed() throws Exception {
        CountDownLatch auditing = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        List<String> outcomes = new CopyOnWriteArrayList<>();
        CredentialProvider provider = new CredentialProvider() {
            @Override
            public CredentialAuthorization authorize(CredentialRef reference, InvocationIdentity identity, Instant now) {
                return new CredentialAuthorization(UUID.randomUUID(), reference, identity);
            }

            @Override
            public ResolvedCredential resolve(CredentialAuthorization authorization, Instant now) {
                return new ResolvedCredential("material".toCharArray(), now.plusSeconds(60));
            }
        };
        InvocationIdentity identity = new InvocationIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        JitGatewayCredential credential = new JitGatewayCredential("direct", new CredentialRef("key"), provider, event -> {
            outcomes.add(event.outcome());
            if (event.outcome().equals("SUCCEEDED")) {
                auditing.countDown();
                try {
                    closed.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }, identity, Clock.systemUTC(), CancellationSignal.create());
        List<Object> used = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try {
                used.add(credential.withMaterial(String::valueOf));
            } catch (GatewayException ended) {
                used.add(ended.code());
            } finally {
                done.countDown();
            }
        });
        assertThat(auditing.await(5, TimeUnit.SECONDS)).isTrue();

        credential.close();
        closed.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(used).containsExactly(GatewayErrorCode.CANCELLED);
        assertThat(outcomes).containsExactly("SUCCEEDED", "ABANDONED");
    }

    private static final InvocationIdentity IDENTITY = new InvocationIdentity(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    private static CredentialProvider counting(AtomicInteger resolutions) {
        return new CredentialProvider() {
            @Override
            public CredentialAuthorization authorize(CredentialRef reference, InvocationIdentity identity, Instant now) {
                return new CredentialAuthorization(UUID.randomUUID(), reference, identity);
            }

            @Override
            public ResolvedCredential resolve(CredentialAuthorization authorization, Instant now) {
                resolutions.incrementAndGet();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return new ResolvedCredential("material".toCharArray(), now.plusSeconds(60));
            }
        };
    }

    // Review 5, items 2 and 3: a failed SUCCEEDED write is compensated and is terminal for the invocation.
    @Test
    void aFailedSuccessAuditIsCompensatedAndNeverResolvesAgain() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        List<String> outcomes = new CopyOnWriteArrayList<>();
        JitGatewayCredential credential = new JitGatewayCredential("direct", new CredentialRef("key"),
                counting(resolutions), event -> {
                    outcomes.add(event.outcome());
                    if (event.outcome().equals("SUCCEEDED")) {
                        throw new IllegalStateException("persisted, then failed to acknowledge");
                    }
                }, IDENTITY, Clock.systemUTC(), CancellationSignal.create());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<GatewayErrorCode> codes = new CopyOnWriteArrayList<>();
        for (int thread = 0; thread < 2; thread++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    credential.withMaterial(String::valueOf);
                } catch (GatewayException failed) {
                    codes.add(failed.code());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(resolutions).hasValue(1);
        assertThat(codes).containsExactly(GatewayErrorCode.CREDENTIAL_UNAVAILABLE, GatewayErrorCode.CREDENTIAL_UNAVAILABLE);
        assertThat(outcomes).containsExactly("SUCCEEDED", "ABANDONED");
    }

    // #174 low 2: the stored terminal failure is not rethrown as one shared instance across callers and threads; each
    // later caller gets its own exception with the same code and message.
    @Test
    void laterCallersGetTheirOwnInstanceOfTheTerminalFailure() {
        JitGatewayCredential credential = new JitGatewayCredential("direct", new CredentialRef("key"),
                counting(new AtomicInteger()), event -> {
                    if (event.outcome().equals("SUCCEEDED")) throw new IllegalStateException("audit down");
                }, IDENTITY, Clock.systemUTC(), CancellationSignal.create());
        GatewayException first = org.assertj.core.api.Assertions.catchThrowableOfType(GatewayException.class,
                () -> credential.withMaterial(String::valueOf));
        GatewayException second = org.assertj.core.api.Assertions.catchThrowableOfType(GatewayException.class,
                () -> credential.withMaterial(String::valueOf));
        GatewayException third = org.assertj.core.api.Assertions.catchThrowableOfType(GatewayException.class,
                () -> credential.withMaterial(String::valueOf));

        assertThat(second).isNotSameAs(first).isNotSameAs(third);
        for (GatewayException later : List.of(second, third)) {
            assertThat(later.code()).isEqualTo(first.code()).isEqualTo(GatewayErrorCode.CREDENTIAL_UNAVAILABLE);
            assertThat(later.getMessage()).isEqualTo(first.getMessage());
        }
    }

    // #179 low 3: a provider's typed denial that coincides with a cancellation is still audited as DENIED_OR_FAILED,
    // while the invocation's outcome remains its ending.
    @Test
    void aTypedDenialRacingACancellationIsStillAudited() throws Exception {
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        CancellationSignal signal = CancellationSignal.create();
        List<String> audits = new CopyOnWriteArrayList<>();
        CredentialProvider denying = new CredentialProvider() {
            @Override
            public CredentialAuthorization authorize(CredentialRef reference, InvocationIdentity identity, Instant now) {
                resolving.countDown();
                try {
                    cancelled.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                throw new com.dvarahq.agentworks.credential.CredentialResolutionException("policy denies this workspace");
            }

            @Override
            public ResolvedCredential resolve(CredentialAuthorization authorization, Instant now) {
                throw new AssertionError("never resolved after a denial");
            }
        };
        JitGatewayCredential credential = new JitGatewayCredential("direct", new CredentialRef("key"), denying,
                event -> audits.add(event.outcome()), IDENTITY, Clock.systemUTC(), signal);
        List<GatewayErrorCode> codes = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try {
                credential.withMaterial(String::valueOf);
            } catch (GatewayException failed) {
                codes.add(failed.code());
            } finally {
                done.countDown();
            }
        });
        assertThat(resolving.await(5, TimeUnit.SECONDS)).isTrue();

        signal.cancel(CancellationSignal.Reason.CALLER);
        cancelled.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        credential.close();

        assertThat(codes).containsExactly(CancellationSignal.failure(CancellationSignal.Reason.CALLER).code());
        assertThat(audits).containsExactly("DENIED_OR_FAILED");
    }

    // Review 5, item 7: a close() that races a failing SUCCEEDED write surfaces the invocation's own ending.
    @Test
    void aCloseRacingAFailingSuccessAuditReportsTheInvocationEnding() throws Exception {
        CountDownLatch auditing = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        CancellationSignal signal = CancellationSignal.create();
        JitGatewayCredential credential = new JitGatewayCredential("direct", new CredentialRef("key"),
                counting(new AtomicInteger()), event -> {
                    if (event.outcome().equals("SUCCEEDED")) {
                        auditing.countDown();
                        try {
                            closed.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        throw new IllegalStateException("audit store unavailable");
                    }
                }, IDENTITY, Clock.systemUTC(), signal);
        List<GatewayErrorCode> codes = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try {
                credential.withMaterial(String::valueOf);
            } catch (GatewayException failed) {
                codes.add(failed.code());
            } finally {
                done.countDown();
            }
        });
        assertThat(auditing.await(5, TimeUnit.SECONDS)).isTrue();

        signal.cancel();
        credential.close();
        closed.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(codes).containsExactly(GatewayErrorCode.CANCELLED);
    }

    // #174 medium 2: a close() racing a failed resolution surfaces the invocation's ending, not CREDENTIAL_UNAVAILABLE.
    @Test
    void aCloseRacingAFailedResolutionReportsTheInvocationEnding() throws Exception {
        for (CancellationSignal.Reason reason : List.of(CancellationSignal.Reason.DEADLINE,
                CancellationSignal.Reason.CALLER)) {
            CountDownLatch resolving = new CountDownLatch(1);
            CountDownLatch closed = new CountDownLatch(1);
            CancellationSignal signal = CancellationSignal.create();
            CredentialProvider failing = new CredentialProvider() {
                @Override
                public CredentialAuthorization authorize(CredentialRef reference, InvocationIdentity identity,
                        Instant now) {
                    return new CredentialAuthorization(UUID.randomUUID(), reference, identity);
                }

                @Override
                public ResolvedCredential resolve(CredentialAuthorization authorization, Instant now) {
                    resolving.countDown();
                    try {
                        closed.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("vault unavailable");
                }
            };
            JitGatewayCredential credential = new JitGatewayCredential("direct", new CredentialRef("key"), failing,
                    event -> { }, IDENTITY, Clock.systemUTC(), signal);
            List<GatewayErrorCode> codes = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread.ofVirtual().start(() -> {
                try {
                    credential.withMaterial(String::valueOf);
                } catch (GatewayException failed) {
                    codes.add(failed.code());
                } finally {
                    done.countDown();
                }
            });
            assertThat(resolving.await(5, TimeUnit.SECONDS)).isTrue();

            signal.cancel(reason);
            credential.close();
            closed.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(codes).containsExactly(CancellationSignal.failure(reason).code());
            assertThat(codes).doesNotContain(GatewayErrorCode.CREDENTIAL_UNAVAILABLE);
        }
    }

    // #174 review 2, item 9: ModelGateway cancels the signal before close(); a resolution failing in between is the
    // invocation's ending, and it is not audited as a denial.
    @Test
    void aCancellationBeforeCloseRacingAFailedResolutionIsTheEndingAndNotADenial() throws Exception {
        for (CancellationSignal.Reason reason : List.of(CancellationSignal.Reason.DEADLINE,
                CancellationSignal.Reason.CALLER)) {
            CountDownLatch resolving = new CountDownLatch(1);
            CountDownLatch cancelled = new CountDownLatch(1);
            CancellationSignal signal = CancellationSignal.create();
            List<String> audits = new CopyOnWriteArrayList<>();
            CredentialProvider failing = new CredentialProvider() {
                @Override
                public CredentialAuthorization authorize(CredentialRef reference, InvocationIdentity identity,
                        Instant now) {
                    return new CredentialAuthorization(UUID.randomUUID(), reference, identity);
                }

                @Override
                public ResolvedCredential resolve(CredentialAuthorization authorization, Instant now) {
                    resolving.countDown();
                    try {
                        cancelled.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("resolution interrupted");
                }
            };
            JitGatewayCredential credential = new JitGatewayCredential("direct", new CredentialRef("key"), failing,
                    event -> audits.add(event.outcome()), IDENTITY, Clock.systemUTC(), signal);
            List<GatewayErrorCode> codes = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread.ofVirtual().start(() -> {
                try {
                    credential.withMaterial(String::valueOf);
                } catch (GatewayException failed) {
                    codes.add(failed.code());
                } finally {
                    done.countDown();
                }
            });
            assertThat(resolving.await(5, TimeUnit.SECONDS)).isTrue();

            // Cancelled, but not yet closed: exactly the window ModelGateway leaves before its try-with-resources.
            signal.cancel(reason);
            cancelled.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            credential.close();

            assertThat(codes).containsExactly(CancellationSignal.failure(reason).code());
            assertThat(audits).doesNotContain("DENIED_OR_FAILED");
        }
    }

    @Test
    void concurrentUsesResolveTheCredentialOnceAndAuditEveryResolution() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        List<CredentialAuditEvent> audits = new CopyOnWriteArrayList<>();
        CredentialProvider slow = new CredentialProvider() {
            @Override
            public CredentialAuthorization authorize(CredentialRef reference, InvocationIdentity identity, Instant now) {
                return new CredentialAuthorization(UUID.randomUUID(), reference, identity);
            }

            @Override
            public ResolvedCredential resolve(CredentialAuthorization authorization, Instant now) {
                resolutions.incrementAndGet();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return new ResolvedCredential("material".toCharArray(), now.plusSeconds(60));
            }
        };
        InvocationIdentity identity = new InvocationIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        JitGatewayCredential credential = new JitGatewayCredential("direct", new CredentialRef("key"), slow, audits::add,
                identity, Clock.systemUTC(), CancellationSignal.create());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<String> seen = new CopyOnWriteArrayList<>();
        for (int thread = 0; thread < 2; thread++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    seen.add(credential.withMaterial(String::valueOf));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        credential.close();

        assertThat(seen).containsExactly("material", "material");
        assertThat(resolutions).hasValue(1);
        assertThat(audits).singleElement().satisfies(audit -> assertThat(audit.outcome()).isEqualTo("SUCCEEDED"));
    }
}
