# Dvara AgentWorks A2A and remote executors

Optional production boundary for versioned external executors. It performs negotiation before execution, uses
fixed-origin authenticated HTTP/SSE, validates every event against the complete invocation fence, and exposes a
server-framework-neutral A2A adapter. It does not own run state: the Java runtime and PostgreSQL remain authoritative.

The module supports `BLACK_BOX` and `INSTRUMENTED` conformance. `DURABLE` is accepted only when checkpoint export,
checkpoint restore, and recovery evidence are advertised. Framework-direct access is always capped at `BLACK_BOX`.
