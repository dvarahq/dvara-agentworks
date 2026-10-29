# What AgentWorks supports

This page tells you which platforms are safe choices for AgentWorks 1.0. “Tested” means the release checks that
combination. “Supported” means it is expected to work and receive fixes.

## Platform support

| Surface | Supported choice | Not supported |
|---|---|---|
| Java | Tested Java 25 distributions | Java 24 or older; Java 26 or newer |
| Spring | Spring Boot 4.1.2 with Spring AI 2.0.1 | Other combinations until qualified |
| PostgreSQL | Current minor releases of PostgreSQL 15–18; PostgreSQL 17 is the reference image | PostgreSQL 14 or older; 19 or newer; H2 in production |
| Docker | Engines 27–29 with Compose v2 | Engines missing required health and security controls |
| Kubernetes | Kubernetes 1.35–1.37 | 1.34 or older; 1.38 or newer |
| CLI | Python 3.11+ on Linux and macOS | Native Windows and Python 3.10 or older |
| Web Console | Current and previous Chrome, Firefox, and Safari | Internet Explorer and browsers without modern web APIs |

H2 is useful for loopback-only local development and tests. Use PostgreSQL for any reachable or production service.
Always install the latest security patch within a supported line.

## What remains compatible

AgentWorks stores enough version information to read durable runs created by compatible older releases. Existing
runs keep the agent release, deployment, executor, and integration versions they started with. A new deployment does
not silently change work already in progress.

Unknown newer formats fail closed instead of being guessed. API clients should ignore response fields they do not
understand, but the server rejects unknown request fields where accepting them could change behavior.

The REST API uses a versioned path. AgentSpec uses `dvarahq.com/v1alpha1`. Changes inside these contracts must remain
backward compatible or arrive through a new version with an announced migration path.

## Safe upgrades

Read the release notes before every upgrade. Keep the previous compatible image available while old runs drain, and
rehearse a mixed-version rollout if old and new workers will overlap. Deprecations are announced before removal;
urgent security fixes may shorten the normal notice period.

AgentWorks checks Java and database compatibility early and refuses combinations it knows are unsafe. Follow the
[Upgrade and rollback](upgrade-and-rollback.md) guide for the operator steps.
