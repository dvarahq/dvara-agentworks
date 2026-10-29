package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.agent.AgentExecutor;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.DurableHumanInteractionCoordinator;
import com.dvarahq.agentworks.runplane.execution.DurableChildRunCoordinator;
import com.dvarahq.agentworks.runplane.execution.CapabilityResolvingRunHandler;
import com.dvarahq.agentworks.runplane.execution.ExecutorRouter;
import com.dvarahq.agentworks.runplane.execution.RunHandler;
import com.dvarahq.agentworks.runplane.execution.RunCapabilityResolver;
import com.dvarahq.agentworks.runplane.execution.RunWorker;
import com.dvarahq.agentworks.runplane.compiled.CompiledReleaseCache;
import com.dvarahq.agentworks.runplane.compiled.CompiledReleaseCachingRunHandler;
import com.dvarahq.agentworks.runplane.compiled.SharedResourceRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Starts the Run Plane worker pool (issue #34). It is opt-in: nothing claims runs unless
 * {@code agentworks.runs.worker.enabled=true}. Properties under {@code agentworks.runs.worker}:
 * Durations accept Spring Boot's formats ({@code 30s}, {@code 5m}) or ISO-8601 ({@code PT30S}); a bare number without
 * a unit fails startup.
 * <ul>
 *   <li>{@code threads} (default 1): concurrent workers on this node;</li>
 *   <li>{@code lease-duration} (default 30s): each claim's lease, heartbeated at a third of it;</li>
 *   <li>{@code idle-backoff} (default PT1S): the pause after a poll that found nothing to do;</li>
 *   <li>{@code claim-quarantine} (default PT5M; at least one second, at most one day) and {@code effect-runtime-grace} (default PT2M): see
 *       {@link RunWorker};</li>
 *   <li>{@code id-prefix} (default the host name plus a random suffix): worker identities recorded on leases.</li>
 * </ul>
 * Each claimed run is executed by the {@link AgentExecutor} whose type its release pinned ({@link ExecutorRouter});
 * every executor that is also a {@link RunHandler} is routable, and two with one type, or none at all, fail startup.
 * Workers claim only runs whose release pins one of the routable types.
 */
@AutoConfiguration(after = {AgentWorksLifecycleAutoConfiguration.class, AgentWorksSpringAiAutoConfiguration.class})
@ConditionalOnBean(DurableRunEngine.class)
@ConditionalOnProperty(prefix = "agentworks.runs.worker", name = "enabled", havingValue = "true")
public final class AgentWorksRunWorkerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ResourceCleanupJournal agentWorksResourceCleanupJournal(Environment environment,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registries) {
        ResourceCleanupJournal journal = new ResourceCleanupJournal(Path.of(environment.getProperty(
                "agentworks.runs.cleanup-evidence.path", "data/runtime-cleanup.pending")), java.time.Clock.systemUTC());
        io.micrometer.core.instrument.MeterRegistry metrics = registries.getIfAvailable();
        if (metrics != null) metrics.gauge("agentworks.resource.cleanup.unresolved.at.startup", journal,
                ResourceCleanupJournal::unresolvedAtStartup);
        return journal;
    }

    @Bean
    @ConditionalOnMissingBean
    CompiledReleaseCache agentWorksCompiledReleaseCache(Environment environment,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registries, ResourceCleanupJournal journal) {
        CompiledReleaseCache cache = new CompiledReleaseCache(environment.getProperty(
                "agentworks.runs.compiled-cache.maximum-entries", Integer.class, 1024),
                duration(environment, "agentworks.runs.compiled-cache.shutdown-grace", Duration.ofSeconds(30)),
                java.time.Clock.systemUTC(), journal::record);
        io.micrometer.core.instrument.MeterRegistry metrics = registries.getIfAvailable();
        if (metrics != null) {
            metrics.gauge("agentworks.compiled.cache.entries", cache, source -> source.snapshot().entries());
            metrics.gauge("agentworks.compiled.cache.references", cache,
                    source -> source.snapshot().activeReferences());
            metrics.gauge("agentworks.compiled.cache.hits", cache, source -> source.snapshot().hits());
            metrics.gauge("agentworks.compiled.cache.misses", cache, source -> source.snapshot().misses());
            metrics.gauge("agentworks.compiled.cache.compile.nanos", cache,
                    source -> source.snapshot().compileNanos());
            metrics.gauge("agentworks.compiled.cache.retirement.lag", cache,
                    source -> source.snapshot().oldestRetirementLagMillis());
            metrics.gauge("agentworks.compiled.cache.failures", cache,
                    source -> source.snapshot().compileFailures() + source.snapshot().closeFailures());
        }
        return cache;
    }

    @Bean
    @ConditionalOnMissingBean
    SharedResourceRegistry agentWorksSharedResourceRegistry(Environment environment,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registries, ResourceCleanupJournal journal) {
        SharedResourceRegistry resources = new SharedResourceRegistry(environment.getProperty(
                "agentworks.runs.shared-resources.maximum-entries", Integer.class, 256),
                duration(environment, "agentworks.runs.shared-resources.shutdown-grace", Duration.ofSeconds(30)),
                java.time.Clock.systemUTC(), journal::record);
        io.micrometer.core.instrument.MeterRegistry metrics = registries.getIfAvailable();
        if (metrics != null) {
            metrics.gauge("agentworks.shared.resources", resources, source -> source.snapshot().resources());
            metrics.gauge("agentworks.shared.resources.leases", resources, source -> source.snapshot().activeLeases());
            metrics.gauge("agentworks.shared.resources.failures", resources,
                    source -> source.snapshot().creationFailures() + source.snapshot().closeFailures());
        }
        return resources;
    }

    @Bean
    @ConditionalOnMissingBean
    SharedResourceMaintenanceLifecycle agentWorksSharedResourceMaintenance(SharedResourceRegistry resources,
            CompiledReleaseCache cache, Environment environment) {
        return new SharedResourceMaintenanceLifecycle(resources, cache, duration(environment,
                "agentworks.runs.shared-resources.maintenance-interval", Duration.ofSeconds(30)));
    }

    @Bean
    @ConditionalOnMissingBean
    ExecutorRouter agentWorksExecutorRouter(ObjectProvider<AgentExecutor> executors,
            ObjectProvider<RunCapabilityResolver> capabilities, CompiledReleaseCache compiledReleases) {
        Map<String, RunHandler> routes = new HashMap<>();
        RunCapabilityResolver resolver = capabilities.getIfAvailable();
        executors.orderedStream().forEach(executor -> {
            if (!(executor instanceof RunHandler handler)) return;
            RunHandler route = resolver == null ? handler : new CapabilityResolvingRunHandler(handler, resolver);
            route = new CompiledReleaseCachingRunHandler(route, compiledReleases);
            if (routes.putIfAbsent(executor.type(), route) != null) {
                throw new IllegalStateException("two AgentExecutor beans handle executor type '" + executor.type() + "'");
            }
        });
        return new ExecutorRouter(routes);
    }

    @Bean
    @ConditionalOnMissingBean
    RunWorkerLifecycle agentWorksRunWorkers(DurableRunEngine engine, ExecutorRouter router, Environment environment,
            ObjectProvider<DurableHumanInteractionCoordinator> interactions,
            ObjectProvider<DurableChildRunCoordinator> children,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registries) {
        RunWorkerLifecycle lifecycle = new RunWorkerLifecycle(engine, router, router.types(), settings(environment),
                interactions.getIfAvailable(), children.getIfAvailable(), registries.getIfAvailable());
        io.micrometer.core.instrument.MeterRegistry metrics = registries.getIfAvailable();
        if (metrics != null) metrics.gauge("agentworks.run.worker.threads", lifecycle, RunWorkerLifecycle::workers);
        return lifecycle;
    }

    static RunWorkerLifecycle.Settings settings(Environment environment) {
        String prefix = "agentworks.runs.worker.";
        return new RunWorkerLifecycle.Settings(environment.getProperty(prefix + "threads", Integer.class, 1),
                environment.getProperty(prefix + "id-prefix", defaultPrefix()),
                duration(environment, prefix + "lease-duration", DurableRunEngine.DEFAULT_LEASE),
                duration(environment, prefix + "idle-backoff", Duration.ofSeconds(1)),
                duration(environment, prefix + "claim-quarantine", RunWorker.DEFAULT_CLAIM_QUARANTINE),
                duration(environment, prefix + "effect-runtime-grace", RunWorker.DEFAULT_EFFECT_RUNTIME_GRACE));
    }

    /**
     * A duration in Spring Boot's formats: {@code 30s}, {@code 5m}, or ISO-8601 such as {@code PT30S}. A bare number is
     * refused at startup, naming the property: its unit would otherwise silently default to milliseconds. The raw value
     * is read with the same relaxed binding as the duration itself, so {@code leaseDuration} or an environment variable
     * such as {@code AGENTWORKS_RUNS_WORKER_LEASEDURATION} is checked too (#181).
     */
    static Duration duration(Environment environment, String name, Duration fallback) {
        Binder binder = Binder.get(environment);
        String raw = binder.bind(name, String.class).orElse(null);
        if (raw != null && raw.strip().matches("[-+]?\\d+")) {
            throw new IllegalArgumentException(name + " needs a unit, for example 30s, 5m, or PT30S (got '"
                    + raw.strip() + "')");
        }
        return binder.bind(name, Duration.class).orElse(fallback);
    }

    /** A worker identity is recorded on every lease; it names the host and is unique per process. */
    private static String defaultPrefix() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (java.io.IOException unknown) {
            host = "worker";
        }
        String safe = host.replaceAll("[^A-Za-z0-9._-]", "-");
        if (safe.isEmpty()) safe = "worker";
        if (safe.length() > 64) safe = safe.substring(0, 64);
        return safe + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
