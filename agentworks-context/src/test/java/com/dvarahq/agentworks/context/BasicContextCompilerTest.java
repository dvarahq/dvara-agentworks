package com.dvarahq.agentworks.context;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BasicContextCompilerTest {
    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000048");
    private static final UUID RELEASE = UUID.fromString("00000000-0000-0000-0000-000000000148");
    private static final UUID REVISION = UUID.fromString("00000000-0000-0000-0000-000000000248");

    private final ContextCompilationCache cache = new ContextCompilationCache(8);
    private final BasicContextCompiler compiler = new BasicContextCompiler(cache, new SlidingWindowCompactionStrategy());

    @Test
    void compilesStablePrefixAndFreshDynamicSuffixDeterministically() {
        ContextItem system = item("system", ContextItemType.SYSTEM_INSTRUCTION, "stable", 5, true,
                ContextLoadingPolicy.ALWAYS, ContextClassification.INTERNAL, WORKSPACE);
        ContextItem first = item("input", ContextItemType.USER_INPUT, "first", 4, true,
                ContextLoadingPolicy.ALWAYS, ContextClassification.INTERNAL, WORKSPACE);
        CompiledContext initial = compiler.compile(request(List.of(system), List.of(first), 12));
        ContextItem second = item("input", ContextItemType.USER_INPUT, "second", 4, true,
                ContextLoadingPolicy.ALWAYS, ContextClassification.INTERNAL, WORKSPACE);
        CompiledContext next = compiler.compile(request(List.of(system), List.of(second), 12));

        assertThat(initial.stableCacheHit()).isFalse();
        assertThat(next.stableCacheHit()).isTrue();
        assertThat(next.stablePrefix()).contains("stable");
        assertThat(next.dynamicContent()).contains("second").doesNotContain("first");
        assertThat(next.estimatedTokens()).isEqualTo(9);
    }

    @Test
    void refusesTenantClassificationDestinationAndMandatoryOverflow() {
        ContextItem wrongTenant = item("foreign", ContextItemType.SKILL, "secret", 1, false,
                ContextLoadingPolicy.ALWAYS, ContextClassification.RESTRICTED, UUID.randomUUID());
        ContextItem classified = item("classified", ContextItemType.SKILL, "restricted", 1, false,
                ContextLoadingPolicy.ALWAYS, ContextClassification.RESTRICTED, WORKSPACE);
        ContextItem redacted = constrained("redacted", ContextItemType.SKILL, true,
                Set.of(ContextDestination.MODEL, ContextDestination.CACHE));
        ContextItem cacheDenied = constrained("model-only", ContextItemType.SKILL, false,
                Set.of(ContextDestination.MODEL));
        CompiledContext denied = compiler.compile(request(List.of(wrongTenant, classified, redacted, cacheDenied),
                List.of(), 8));
        assertThat(denied.decisions()).extracting(ContextDecision::reason)
                .containsExactlyInAnyOrder("TENANT_MISMATCH", "CLASSIFICATION_DENIED", "REDACTED", "CACHE_DENIED");

        ContextItem tooLarge = item("required", ContextItemType.USER_INPUT, "required", 9, true,
                ContextLoadingPolicy.ALWAYS, ContextClassification.INTERNAL, WORKSPACE);
        assertThatThrownBy(() -> compiler.compile(request(List.of(), List.of(tooLarge), 8)))
                .isInstanceOf(ContextCompilationException.class)
                .extracting("code").isEqualTo("CONTEXT_MANDATORY_OVERFLOW");
        assertThatThrownBy(() -> new ContextCompilationRequest(UUID.randomUUID(), key(),
                new ContextBudget(8, Map.of()), List.of(), List.of(), Set.of(), ContextDestination.MODEL,
                ContextClassification.CONFIDENTIAL, true, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("cache key tenant mismatch");
    }

    @Test
    void exactDedupeLoadingPolicyCompactionAndRetirementAreExplicit() {
        ContextItem stable = item("stable", ContextItemType.SKILL, "same", 3, false,
                ContextLoadingPolicy.ALWAYS, ContextClassification.INTERNAL, WORKSPACE);
        ContextItem duplicate = item("duplicate", ContextItemType.WORKING_MEMORY, "same", 3, false,
                ContextLoadingPolicy.ALWAYS, ContextClassification.INTERNAL, WORKSPACE);
        ContextItem demand = item("optional", ContextItemType.SKILL, "only-on-demand", 3, false,
                ContextLoadingPolicy.ON_DEMAND, ContextClassification.INTERNAL, WORKSPACE);
        ContextItem latest = item("latest", ContextItemType.CONVERSATION_MEMORY, "latest", 4, false,
                ContextLoadingPolicy.ALWAYS, ContextClassification.INTERNAL, WORKSPACE);
        CompiledContext result = compiler.compile(new ContextCompilationRequest(WORKSPACE, key(),
                new ContextBudget(7, Map.of()), List.of(stable), List.of(duplicate, demand, latest),
                Set.of("optional"), ContextDestination.MODEL, ContextClassification.CONFIDENTIAL, true, true));

        assertThat(result.decisions()).anySatisfy(decision -> {
            assertThat(decision.itemId()).isEqualTo("duplicate");
            assertThat(decision.reason()).isEqualTo("EXACT_DUPLICATE");
        });
        assertThat(result.compacted()).isTrue();
        assertThat(compiler.retire(WORKSPACE, RELEASE, REVISION)).isEqualTo(1);
        assertThat(cache.size()).isZero();
    }

    @Test
    void cachePolicyDependencyAndReservationsControlCompilation() {
        ContextItem oldStable = item("system", ContextItemType.SYSTEM_INSTRUCTION, "old", 2, false,
                ContextLoadingPolicy.ALWAYS, ContextClassification.INTERNAL, WORKSPACE);
        ContextItem changedStable = item("system", ContextItemType.SYSTEM_INSTRUCTION, "changed", 2, false,
                ContextLoadingPolicy.ALWAYS, ContextClassification.INTERNAL, WORKSPACE);
        ContextItem memory = item("memory", ContextItemType.CONVERSATION_MEMORY, "memory", 4, false,
                ContextLoadingPolicy.ALWAYS, ContextClassification.INTERNAL, WORKSPACE);
        ContextItem input = item("input", ContextItemType.USER_INPUT, "input", 4, false,
                ContextLoadingPolicy.ALWAYS, ContextClassification.INTERNAL, WORKSPACE);

        compiler.compile(request(List.of(oldStable), List.of(), 8));
        CompiledContext bypassed = compiler.compile(new ContextCompilationRequest(WORKSPACE, key(),
                new ContextBudget(8, Map.of()), List.of(changedStable), List.of(), Set.of(),
                ContextDestination.MODEL, ContextClassification.CONFIDENTIAL, false, true));
        assertThat(cache.size()).isEqualTo(1);
        CompiledContext reserved = compiler.compile(new ContextCompilationRequest(WORKSPACE,
                new ContextCacheKey(WORKSPACE, RELEASE, REVISION, digest("policy-2"), digest("dependencies-2")),
                new ContextBudget(6, Map.of(ContextItemType.USER_INPUT, 4)), List.of(), List.of(memory, input),
                Set.of(), ContextDestination.MODEL, ContextClassification.CONFIDENTIAL, true, false));

        assertThat(bypassed.stablePrefix()).contains("changed").doesNotContain("old");
        assertThat(bypassed.stableCacheHit()).isFalse();
        assertThat(cache.size()).isEqualTo(2);
        assertThat(reserved.dynamicContent()).contains("input").doesNotContain("memory");
    }

    @Test
    void stableCacheSeparatesDynamicBudgetLayoutsWithoutCachingRunContent() {
        ContextItem optionalStable = item("optional-system", ContextItemType.SYSTEM_INSTRUCTION, "optional", 7, false,
                ContextLoadingPolicy.ALWAYS, ContextClassification.INTERNAL, WORKSPACE);
        ContextItem shortInput = item("input", ContextItemType.USER_INPUT, "short", 4, true,
                ContextLoadingPolicy.ALWAYS, ContextClassification.INTERNAL, WORKSPACE);
        ContextItem longInput = item("input", ContextItemType.USER_INPUT, "long", 8, true,
                ContextLoadingPolicy.ALWAYS, ContextClassification.INTERNAL, WORKSPACE);

        CompiledContext shortRun = compiler.compile(request(List.of(optionalStable), List.of(shortInput), 12));
        CompiledContext longRun = compiler.compile(request(List.of(optionalStable), List.of(longInput), 12));

        assertThat(shortRun.stablePrefix()).contains("optional");
        assertThat(longRun.stablePrefix()).isBlank();
        assertThat(longRun.dynamicContent()).contains("long");
        assertThat(longRun.stableCacheHit()).isFalse();
    }

    @Test
    void boundedCacheSerializesConcurrentMissesWithoutExceedingItsLimit() throws Exception {
        ContextCompilationCache oneEntry = new ContextCompilationCache(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger full = new AtomicInteger();
        try (var tasks = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 32; index++) {
                int candidate = index;
                tasks.submit(() -> {
                    try {
                        oneEntry.getOrCompile(new ContextCacheKey(WORKSPACE, RELEASE,
                                new UUID(REVISION.getMostSignificantBits(), candidate), digest("policy"),
                                digest("dependency-" + candidate)), 8,
                                () -> new ContextCompilationCache.Entry("prefix", 1, List.of()));
                        successes.incrementAndGet();
                    } catch (ContextCompilationException refused) {
                        assertThat(refused.code()).isEqualTo("CONTEXT_CACHE_FULL");
                        full.incrementAndGet();
                    }
                });
            }
            tasks.shutdown();
            assertThat(tasks.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(successes).hasValue(1);
        assertThat(full).hasValue(31);
        assertThat(oneEntry.size()).isEqualTo(1);
    }

    private static ContextCompilationRequest request(List<ContextItem> stable, List<ContextItem> dynamic, int budget) {
        return new ContextCompilationRequest(WORKSPACE, key(), new ContextBudget(budget, Map.of()), stable, dynamic,
                Set.of(), ContextDestination.MODEL, ContextClassification.CONFIDENTIAL, true, true);
    }

    private static ContextCacheKey key() {
        return new ContextCacheKey(WORKSPACE, RELEASE, REVISION, digest("policy"), digest("dependencies"));
    }

    private static ContextItem item(String id, ContextItemType type, String content, int tokens, boolean mandatory,
            ContextLoadingPolicy loading, ContextClassification classification, UUID workspace) {
        return new ContextItem(id, type, content, tokens, 0.5, 10, mandatory, loading, 1,
                new ContextProvenance("test", id, digest(content), "verified"),
                new ContextConstraints(workspace, null, classification,
                        Set.of(ContextDestination.MODEL, ContextDestination.CACHE), false, "run"));
    }

    private static ContextItem constrained(String id, ContextItemType type, boolean redacted,
            Set<ContextDestination> destinations) {
        return new ContextItem(id, type, id, 1, 0.5, 10, false, ContextLoadingPolicy.ALWAYS, 1,
                new ContextProvenance("test", id, digest(id), "verified"),
                new ContextConstraints(WORKSPACE, null, ContextClassification.INTERNAL, destinations, redacted,
                        "run"));
    }

    private static String digest(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
