# Troubleshooting

Start with the error `code`, trace ID, run ID, and exact AgentWorks version. Create a redacted **support bundle** when
possible. Never paste tokens, prompts, tool arguments, secrets, or customer data into an issue.

| What you see | What it usually means | What to do |
|---|---|---|
| `UNSUPPORTED_RUNTIME` | Java or PostgreSQL is outside the supported range | Use a version from the [compatibility table](operations/compatibility.md) |
| 403 or `WORKSPACE_SCOPE_UNAVAILABLE` | The signed-in user or token cannot access that workspace | Check membership and permissions; do not add a workspace header |
| `GATEWAY_NOT_CONFIGURED` or `MODEL_ALIAS_UNKNOWN` | The release cannot resolve its model | Fix the gateway or alias, then create a new release |
| `IDEMPOTENCY_KEY_REUSED` | The same key was used with different request content | Send the original request or use a new key for genuinely new work |
| A stream asks you to reconnect | The connection ended but the run may still be active | Reconnect with the last event ID |
| A run stays queued | No compatible worker can claim it, or admission limits are full | Check workers, quarantine, placement, and limits |
| An old release will not retire | Durable work is still attached to it | Read the reported blockers and finish, cancel, or revoke that work safely |
| Readiness is down | A required dependency—usually PostgreSQL—is unavailable | Check TLS, credentials, Flyway state, RLS, and connection-pool use |
| **Migration checksum mismatch** | A released migration was edited | Stop, restore the original file, and add a new migration; do not bless the edit with `repair` |
| Sandbox cleanup is pending | The original provider could not remove a resource | Restore the provider and reconcile; do not delete a resource with mismatched ownership |

For security concerns, use [private security reporting](security/operations.md), not a public issue.
