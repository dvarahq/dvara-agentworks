# Configure AgentWorks

AgentWorks uses normal Spring Boot configuration. A property such as `agentworks.runs.worker.enabled` can also be an
environment variable such as `AGENTWORKS_RUNS_WORKER_ENABLED`; this is Spring Boot **relaxed binding**.

Use configuration files for ordinary settings. Use mounted secret files or a secret manager for passwords and API
keys. Environment variables can be visible to child processes, so they are best kept for local development.

## Common settings

| What you are configuring | Prefix |
|---|---|
| Model providers and model aliases | `agentworks.gateway.*` |
| Run workers, limits, admission, streams, and recovery | `agentworks.runs.*` |
| Authentication, sessions, login limits, and bootstrap | `agentworks.security.*` |
| Separate Control Plane and Run Plane databases | `agentworks.planes.*` |
| Metrics, health checks, and support bundles | `agentworks.observability.*` |
| Java client connection | `agentworks.client.*` |
| Sandboxes and Kubernetes execution | `agentworks.sandbox.*` |
| Stored artifacts and cleanup | `agentworks.artifacts.*` |

For a model gateway, configure an ID, adapter type, base URL, credential reference, models, and capabilities under
`agentworks.gateway.gateways.<id>.*`. Keep the actual credential outside the ordinary configuration file.

For production, configure separate least-privilege database connections under `agentworks.planes.control-plane.*`
and `agentworks.planes.run-plane.*`.

## Advanced setting groups

These groups exist for more specialized installations:

- `agentworks.agents.*`, `agentworks.console.*`, `agentworks.credentials.*`, `agentworks.jdbc.*`,
  `agentworks.lifecycle.*`, and `agentworks.spring-ai.*` enable or tune core features.
- `agentworks.tenancy.*` and `agentworks.development.*` control the loopback-only local experience. They are not a
  production authorization shortcut.
- `agentworks.idempotency.*` holds versioned HMAC keys. Keep every key version still referenced by stored records.
- `agentworks.release-evidence.*`, `agentworks.runtime.*`, and `agentworks.node-id` identify and verify the exact
  software allowed to execute durable work.
- `agentworks.recovery.*`, `agentworks.operations.*`, and `agentworks.children.*` tune durable background work.
- `agentworks.capabilities.*` and `agentworks.context.*` control capability selection and context caching.
- `agentworks.compatibility.*` contains explicit compatibility gates. Embedded H2 remains test-only.

Standard Spring settings still configure the primary data source, server address, trusted proxies, Actuator,
Prometheus, and OpenTelemetry.

## CLI settings

The CLI reads the active profile and also accepts these process-local overrides:

- `AGENTWORKS_URL`
- `AGENTWORKS_PROFILE`
- `AGENTWORKS_TOKEN`

A profile can use `--token-env` or an owner-only file with `--token-file`, so the token itself is not saved in the
profile.

The Compose example lists its small set of variables in `deploy/compose/.env.example`. Kubernetes values are
documented and validated by the AgentWorks Helm chart.
