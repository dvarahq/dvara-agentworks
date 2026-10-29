package com.dvarahq.agentworks.autoconfigure;

import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The single local-development gate: {@code agentworks.development.local=true}, which only the server's {@code local}
 * profile sets. It enables development conveniences that must never reach a reachable installation, today only the
 * deterministic fake model gateway (issue #33). It grants no unauthenticated access: every request still
 * authenticates (issue #74).
 *
 * <p>The flag is valid only while the server binds to a loopback address ({@code server.address}). Setting it on a
 * server that binds any other or every interface fails startup instead of silently enabling or ignoring it.
 */
final class AgentWorksLocalDevelopment {

    static final String FLAG = "agentworks.development.local";
    static final String SERVER_ADDRESS = "server.address";

    private AgentWorksLocalDevelopment() {
    }

    /** True when the flag is set on a loopback-bound server; throws when it is set on any other server. */
    static boolean enabled(Environment environment) {
        if (!environment.getProperty(FLAG, Boolean.class, false)) return false;
        if (!loopback(environment.getProperty(SERVER_ADDRESS))) {
            throw new IllegalStateException(FLAG + "=true requires " + SERVER_ADDRESS
                    + " to be a loopback address (the local profile binds 127.0.0.1); refusing to start");
        }
        return true;
    }

    static String requirement() {
        return FLAG + "=true on a server bound to a loopback address (the local profile)";
    }

    private static boolean loopback(String address) {
        if (address == null || address.isBlank()) return false;
        try {
            for (InetAddress resolved : InetAddress.getAllByName(address.strip())) {
                if (!resolved.isLoopbackAddress()) return false;
            }
            return true;
        } catch (UnknownHostException unresolvable) {
            return false;
        }
    }
}
