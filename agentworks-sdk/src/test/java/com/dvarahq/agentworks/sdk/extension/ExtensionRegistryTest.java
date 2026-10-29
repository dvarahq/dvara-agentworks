package com.dvarahq.agentworks.sdk.extension;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtensionRegistryTest {
    interface Tool { String id(); }

    @Test void explicitProvidersAreTypedSortedAndDiscoverable() {
        Tool tool = () -> "tool";
        AgentWorksExtension extension = () -> List.of(new ProviderRegistration<>(ProviderKind.TOOL, "reference-tool",
                Tool.class, tool, Set.of("bounded")));
        ExtensionRegistry registry = ExtensionRegistry.load(getClass().getClassLoader(), List.of(extension));
        assertThat(registry.providers(ProviderKind.TOOL)).extracting(ProviderRegistration::id)
                .containsExactly("reference-tool");
        assertThat(registry.provider(ProviderKind.TOOL, "reference-tool", Tool.class)).containsSame(tool);
    }

    @Test void duplicateProviderIdentityFailsClosed() {
        Tool tool = () -> "tool";
        AgentWorksExtension extension = () -> List.of(new ProviderRegistration<>(ProviderKind.TOOL, "duplicate",
                Tool.class, tool, Set.of()));
        assertThatThrownBy(() -> ExtensionRegistry.load(getClass().getClassLoader(), List.of(extension, extension)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicate provider");
    }
}
