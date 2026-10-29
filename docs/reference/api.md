# Use the AgentWorks API

AgentWorks exposes one versioned HTTP API under `/api/v1`. The Web Console, CLI, and Java SDK all use this same API.

## Find the exact API description

A running server publishes its OpenAPI 3.1 document at:

```text
GET /api/v1/openapi.yaml
```

Open that document in Swagger UI, an IDE, or your preferred API client to see every request and response field. You
can also save it with `agentworks diagnostic openapi`.

## Authentication and workspaces

Send an API token as a bearer token. AgentWorks gets the workspace from the authenticated user; a request body or
header cannot choose another workspace.

## Errors

Errors use the RFC 9457 problem format. Check the stable `code` first, then the human-readable message and trace ID.
Never log or publish the bearer token or customer content while troubleshooting.

Lists return:

```json
{"items": [], "nextCursor": null}
```

Pass `nextCursor` back to fetch the next page.

## Follow a run live

The run stream uses Server-Sent Events (SSE). Each event has an ordered ID such as `<runId>:<sequence>`. If the
connection drops, reconnect with `Last-Event-ID` to continue without skipping events. If AgentWorks says the old
history is no longer available, reload the paged event history before continuing.

## Safe writes

Creation and lifecycle operations may require an idempotency key. Reusing the same key with the same request is safe;
using it with different content is rejected. Operations that use optimistic concurrency require the latest ETag in
`If-Match`.

Use the [CLI](../cli.md) or [Java SDK](../JAVA-SDK.md) if you do not want to build requests directly.
