# Configure AgentWorks

AgentWorks uses normal Spring Boot configuration. A property such as `agentworks.runs.worker.enabled` can also be an
environment variable such as `AGENTWORKS_RUNS_WORKER_ENABLED`; this is Spring Boot **relaxed binding**.

The complete supported list is the
[machine-readable configuration schema](../../agentworks-server/src/main/resources/META-INF/agentworks/configuration-schema.json).
Each entry names its owner, type, default, limits, sensitivity, restart behavior, first supported version, and lifecycle
status. That schema—not examples copied between deployment files—is the source of truth.

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

## What happens at startup

AgentWorks checks its own settings before Spring creates the application context. This is intentionally earlier than
database migration, worker leasing, exposure publication, or sandbox provisioning.

- An unknown or misspelled `agentworks.*` key stops startup by default.
- A removed key stops startup and names its replacement. A deprecated key produces a warning.
- Invalid booleans, numbers, durations, sizes, URLs, and paths stop startup with the property name and a safe reason.
- The `production` and `prod` profiles reject local-development mode, the embedded database, insecure session cookies,
  missing TLS or trusted-proxy configuration, and obvious placeholder secret material.
- Optional components can remain unavailable without pretending that a required setting is valid. Required dependency
  outages continue to appear in readiness health.

For a planned compatibility window, set `agentworks.configuration.unknown-keys=warn`. Do not use that setting to hide
typos in a release qualification: the default and supported production policy is `error`.

## Safe effective-configuration diagnostics

Set `agentworks.configuration.diagnostics=true` to log explicitly configured AgentWorks keys, their winning origin,
and their effective values during startup. Secret material, credential references, database connection details, and
filesystem paths are always printed as `<redacted>`. Origins are broad categories such as `environment-variable` or
`configuration-file`; local filenames are not exposed.

Spring Boot precedence is deterministic, from highest to lowest: command-line arguments, JVM system properties,
environment variables, configuration files, and profile defaults. Diagnostics report the winning source.

## Upgrades and renamed settings

Configuration changes are part of the compatibility contract. Before an upgrade:

1. validate the new image or package with the same profiles and mounted configuration used in production;
2. resolve every deprecation warning before it becomes a removal in a future major version;
3. never copy a secret value into a replacement property—use a credential reference or mounted secret; and
4. keep `agentworks.configuration.unknown-keys=error` in the final validation.

Removed settings are never silently ignored. The versioned compatibility policy and golden upgrade fixtures are in
`config/compatibility/`.
