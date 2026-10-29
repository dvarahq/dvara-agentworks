package com.dvarahq.agentworks.sdk.extension;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;

/** Immutable registry combining ServiceLoader extensions and explicitly supplied framework beans. */
public final class ExtensionRegistry {
    private final Map<Key, ProviderRegistration<?>> registrations;

    private ExtensionRegistry(Map<Key, ProviderRegistration<?>> registrations) {
        this.registrations = Map.copyOf(registrations);
    }

    public static ExtensionRegistry load(ClassLoader loader, Collection<? extends AgentWorksExtension> explicit) {
        Objects.requireNonNull(loader, "loader");
        List<AgentWorksExtension> extensions = new ArrayList<>();
        ServiceLoader.load(AgentWorksExtension.class, loader).forEach(extensions::add);
        if (explicit != null) extensions.addAll(explicit);
        extensions.sort(Comparator.comparing(extension -> extension.getClass().getName()));
        Map<Key, ProviderRegistration<?>> result = new LinkedHashMap<>();
        for (AgentWorksExtension extension : extensions) {
            Collection<ProviderRegistration<?>> providers = Objects.requireNonNull(extension.providers(), "providers");
            for (ProviderRegistration<?> registration : providers) {
                Key key = new Key(registration.kind(), registration.id());
                if (result.putIfAbsent(key, registration) != null) {
                    throw new IllegalStateException("duplicate provider registration: " + key.kind + "/" + key.id);
                }
            }
        }
        return new ExtensionRegistry(result);
    }

    public List<ProviderRegistration<?>> providers(ProviderKind kind) {
        return registrations.values().stream().filter(value -> value.kind() == kind)
                .sorted(Comparator.comparing(ProviderRegistration::id)).toList();
    }

    public <T> Optional<T> provider(ProviderKind kind, String id, Class<T> contract) {
        ProviderRegistration<?> registration = registrations.get(new Key(kind, id));
        if (registration == null) return Optional.empty();
        if (!contract.isAssignableFrom(registration.contract())) {
            throw new IllegalArgumentException("registered provider has contract " + registration.contract().getName());
        }
        return Optional.of(contract.cast(registration.provider()));
    }

    private record Key(ProviderKind kind, String id) { }
}
