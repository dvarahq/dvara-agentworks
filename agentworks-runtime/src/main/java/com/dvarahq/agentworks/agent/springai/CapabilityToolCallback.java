package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.capability.ToolId;
import org.springframework.ai.tool.ToolCallback;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** An application callback bound explicitly to the exact versioned capability identity it implements. */
public record CapabilityToolCallback(ToolId id, ToolCallback callback, Optional<String> credentialName) {
    private static final Pattern CREDENTIAL_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,63}$");

    public CapabilityToolCallback(ToolId id, ToolCallback callback) {
        this(id, callback, Optional.empty());
    }

    public CapabilityToolCallback(ToolId id, ToolCallback callback, String credentialName) {
        this(id, callback, Optional.of(Objects.requireNonNull(credentialName, "credentialName")));
    }

    public CapabilityToolCallback {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(callback, "callback");
        credentialName = Objects.requireNonNull(credentialName, "credentialName");
        credentialName.ifPresent(name -> {
            if (!CREDENTIAL_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("credentialName must be an exact logical credential name");
            }
        });
        if (credentialName.isPresent() != (callback instanceof CredentialedToolCallback)) {
            throw new IllegalArgumentException("a credential binding requires exactly a CredentialedToolCallback");
        }
    }
}
