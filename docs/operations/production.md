# Production checklist

The local Compose setup is for evaluation. Before real users or real data reach AgentWorks, work through this list.

## Protect the edges

- Use TLS for users, PostgreSQL, model providers, MCP/A2A services, telemetry, and backups.
- Tell AgentWorks exactly which reverse proxy it can trust.
- Keep provider and database secrets in a secret manager or mounted files.
- Restrict outbound network access to the services the agents actually need.
- Pin application and sandbox images by digest rather than a moving tag.

## Protect the database

- Use separate identities for migrations, the Control Plane, and run workers.
- Enable and test PostgreSQL RLS so one workspace cannot read another.
- Encrypt database storage, WAL, and backups.
- Rehearse point-in-time recovery (PITR), not just backup creation.

See [PostgreSQL production](postgresql-production.md) for the detailed setup.

## Size the service

Set CPU/memory requests and limits for every replica. The chart defaults are a starting point, not a capacity plan.
Watch queue delay, heap, database connections, model latency, and worker utilization, then tune with real workloads.

## Verify before traffic

Check:

- startup, liveness, and readiness;
- administrator bootstrap and normal sign-in;
- cross-workspace access denial;
- one complete agent run and a stream reconnect;
- graceful shutdown and recovery on another worker;
- metrics, alerts, traces, and a redacted support bundle.

Readiness means required dependencies are usable. It does not mean every queue is empty or every old release can
retire.

## Plan for failure

| Failure | Safe response |
|---|---|
| Server or worker stops | Start a compatible replacement; it recovers from PostgreSQL |
| Database is unavailable | Stop accepting work, restore the database, then reconcile |
| External action has an unknown result | Preserve the uncertain state; do not blindly repeat it |
| Old release will not drain | Inspect its blockers and keep the compatible runtime available |
| Data is lost or corrupted | Restore with the rehearsed PITR process in isolation, validate, then reopen traffic |

Continue with [Packaging](packaging.md), [Observability](observability.md), and
[Security operations](../security/operations.md).
