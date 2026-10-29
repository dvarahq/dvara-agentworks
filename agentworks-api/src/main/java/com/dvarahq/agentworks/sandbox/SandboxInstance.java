package com.dvarahq.agentworks.sandbox;

import com.dvarahq.agentworks.domain.sandbox.SandboxRef;
import com.dvarahq.agentworks.domain.sandbox.SandboxSnapshot;
import com.dvarahq.agentworks.domain.sandbox.SandboxState;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public interface SandboxInstance extends AutoCloseable {
    SandboxRef reference();
    SandboxState state();
    Path resolveWorkspacePath(String relativePath);
    String execute(List<String> command, Duration timeout) throws InterruptedException;
    /** Bounded protocol output for callers that apply guardrails before doing their own artifact offload. */
    default String executeRaw(List<String> command, Duration timeout) throws InterruptedException {
        return execute(command, timeout);
    }
    SandboxSnapshot suspend();
    @Override void close();
}
