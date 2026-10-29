package com.dvarahq.agentworks.sandbox.docker;

/** Typed failure raised by the Docker sandbox adapter. */
public class DockerSandboxException extends RuntimeException {
    public DockerSandboxException(String message) {
        super(message);
    }

    public DockerSandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}

final class DockerSandboxTimeoutException extends DockerSandboxException {
    DockerSandboxTimeoutException(String message) {
        super(message);
    }
}
