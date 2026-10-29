# Known limitations

AgentWorks 1.0 focuses on durable execution and safe operations. It does not try to include every part of an AI
platform.

It currently does **not** include:

- a visual builder or marketplace;
- a hosted control plane;
- a built-in production model provider or secret manager;
- automatic active-run migration from an old release to a new one;
- a bundled PostgreSQL service, TLS proxy, telemetry backend, or object store;
- JPA, JTA, Kafka, Redis, or another distributed transaction coordinator;
- VM-grade isolation for hostile native code;
- exactly-once guarantees from an external API that does not support them.

PostgreSQL is required for production correctness. H2 is only for local tests. Docker shares the host kernel, so use
dedicated nodes or stronger VM-style isolation for hostile code.

These are current product boundaries, not promises about a future roadmap. Check [Compatibility](operations/compatibility.md)
and the [Production checklist](operations/production.md) before deployment.
