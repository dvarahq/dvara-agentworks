# See what AgentWorks is doing

Good observability should answer simple questions: Is the service healthy? Is work moving? Are retries recovering?
Is the database keeping up? AgentWorks provides health endpoints, structured logs, metrics, and traces for those
questions.

Telemetry export is off by default. Enable the `prometheus` profile for an authenticated metrics endpoint, the
`otlp` profile for OpenTelemetry export, or both.

## What to watch

| Question | Useful signal |
|---|---|
| Can this instance receive traffic? | Readiness health, HTTP errors, and request latency. |
| Are workers doing useful work? | `agentworks.run.worker.threads`, `.polls`, and `.poll.duration`. |
| Are claims being lost? | `agentworks.run.lease.losses`. |
| Is recovery working? | `agentworks.run.recoveries` and `.recovery.delay`. |
| Is durable delivery falling behind? | `agentworks.recovery.outbox.backlog` and `.oldest.age`. |
| Are resources cleaning up? | `agentworks.resource.cleanup.unresolved.at.startup`. |
| Is PostgreSQL healthy? | Connection use, query latency, locks, storage, WAL, and replication lag. |
| Are users seeing problems? | HTTP throughput, error rate, and latency. |

## Logs and traces

Logs carry safe correlation identifiers. Traces connect an API request to admission, execution, tools, and model
calls. Search by a correlation ID when investigating one operation, then use metrics to decide whether the problem
is isolated or widespread.

Prompts, tool arguments, model responses, credentials, tenant names, and other user content do not belong in metric
labels or ordinary logs. Dynamic metric labels are capped to prevent accidental memory and monitoring-cost spikes.

## When you need support

Capture the time window, application version or image digest, symptoms, safe correlation identifiers, relevant
metrics, and a redacted support bundle. Review it before sharing. Never include passwords, tokens, prompts, tool
arguments, model output, or customer data.
