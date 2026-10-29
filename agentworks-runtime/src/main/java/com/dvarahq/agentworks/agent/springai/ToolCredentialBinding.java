package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.credential.JitCredentialToolInvoker;
import com.dvarahq.agentworks.credential.CredentialUseRequest;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Objects;

/** One run's immutable reference and identity bound to a credential-aware callback. */
final class ToolCredentialBinding {
    private final JitCredentialToolInvoker invoker;
    private final CredentialUseRequest request;

    ToolCredentialBinding(JitCredentialToolInvoker invoker, CredentialRef reference, InvocationIdentity identity,
            java.util.UUID agentId) {
        this.invoker = Objects.requireNonNull(invoker, "invoker");
        this.request = CredentialUseRequest.forAgent(reference, identity, agentId);
    }

    String invoke(CredentialedToolCallback callback, String input, ToolContext context) {
        try {
            return invoker.invoke(request, material -> {
                try {
                    return callback.call(input, context, material);
                } catch (RuntimeException callbackFailure) {
                    throw new CallbackFailure(callbackFailure);
                }
            });
        } catch (CallbackFailure callbackFailure) {
            throw callbackFailure.original();
        } catch (RuntimeException unavailable) {
            throw new OutputRejectedException("TOOL_CREDENTIAL_UNAVAILABLE",
                    "the tool credential could not be authorized or resolved");
        }
    }

    @Override
    public String toString() {
        return "ToolCredentialBinding[reference=<redacted>, identity=<redacted>]";
    }

    /** Separates failures from the already-authorized callback from failures at the credential boundary. */
    private static final class CallbackFailure extends RuntimeException {
        private final RuntimeException original;

        private CallbackFailure(RuntimeException original) {
            super(null, original, false, false);
            this.original = original;
        }

        private RuntimeException original() {
            return original;
        }
    }
}
