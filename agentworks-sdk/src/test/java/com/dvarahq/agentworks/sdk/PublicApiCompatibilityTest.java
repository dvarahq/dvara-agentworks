package com.dvarahq.agentworks.sdk;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicApiCompatibilityTest {
    @Test void frozenV1EntryPointsRemainBinaryDiscoverable() throws Exception {
        List<String> signatures;
        try (var input = getClass().getResourceAsStream("/sdk-public-api-v1.txt")) {
            signatures = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)).lines().toList();
        }
        for (String signature : signatures) {
            int hash = signature.indexOf('#');
            int open = signature.indexOf('(', hash);
            String owner = signature.substring(0, hash);
            String name = signature.substring(hash + 1, open);
            String arguments = signature.substring(open + 1, signature.length() - 1);
            Class<?>[] types = arguments.isEmpty() ? new Class<?>[0]
                    : java.util.Arrays.stream(arguments.split(",")).map(this::load).toArray(Class<?>[]::new);
            Method method = Class.forName(owner).getMethod(name, types);
            assertThat(method).as(signature).isNotNull();
        }
    }

    @Test void incompatibleExtensionVersionHasActionableBomGuidance() {
        assertThatThrownBy(() -> SdkCompatibility.requireContractVersion("provider", 2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("agentworks-bom")
                .hasMessageContaining("mixed versions");
    }

    private Class<?> load(String name) {
        try { return Class.forName(name); }
        catch (ClassNotFoundException exception) { throw new AssertionError("missing public API type " + name, exception); }
    }
}
