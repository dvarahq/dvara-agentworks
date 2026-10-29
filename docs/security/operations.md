# Keep an AgentWorks installation safe

AgentWorks starts from a simple rule: a user or worker gets only the authority it needs, for the workspace and action
it is handling now. PostgreSQL row-level security, separate database roles, bounded requests, non-root containers,
and restricted network access provide additional layers.

## Handle credentials safely

- Put credential references in agent definitions, never secret values.
- Resolve a secret only when it is needed and only for the correct workspace, operation, and audience.
- Do not place tokens, prompts, tool arguments, or model responses in logs or telemetry.
- Rotate a credential immediately if it may have appeared in output or a support bundle.

A delegated identity cannot choose its own workspace or continuation. Revoking access blocks new effectful work at
the next enforceable boundary while preserving truthful evidence about work already in flight.

## Share diagnostic data carefully

Redaction is a safety net, not permission to collect sensitive content. Review every support bundle before sharing
it. Keep useful metadata and event types; remove credentials, personal information, prompts, arguments, and model
content.

## Understand the Sandbox boundary

Docker and Kubernetes containers share a host kernel. Use dedicated nodes, a VM, or a microVM when running hostile
native code. Add operating-system controls and network firewalls appropriate to your environment. The portable
Sandbox refuses capabilities it cannot enforce instead of pretending to provide them.

## Report a vulnerability privately

Do not post exploit details or tenant data in a public issue. Use GitHub private vulnerability reporting for the
repository when available, or the private security contact published with the release. Include the affected version
or image digest, impact, a minimal safe reproduction, and a way to contact you. Remove all credentials and customer
content first.

The [threat model](threat-model.md) explains the major risks, built-in controls, and operator responsibilities.
