# Connect models, tools, MCP, A2A, and skills

AgentWorks is provider-neutral. You choose the model providers and external systems your agent may use.

## Models

The easiest option is an OpenAI-compatible endpoint. Configure its base URL, model aliases, and a credential
reference. AgentWorks resolves the real credential only when it is needed and does not store the secret in the agent
definition, release, event history, or logs.

A release turns each friendly model alias into a concrete, pinned model choice. Changing an alias later affects a new
release, not a run already in progress.

## Tools

Tools let an agent perform actions such as calling an API, reading a file, or running a command. Register only the
tools the agent needs. AgentWorks checks the tool's input, permissions, limits, credentials, and isolation before it
runs.

Use a sandbox for filesystem and command tools. HTTP tools deny private network destinations by default.

## MCP

AgentWorks can connect to MCP servers over local stdio or streamable HTTP. Tool discovery is captured in the release,
so a surprise change on an MCP server does not silently change an existing agent.

Treat every MCP server and its output as untrusted. Give it the smallest network and credential access possible.

## A2A and remote agents

A2A lets AgentWorks call or expose another agent over authenticated HTTP and event streams. AgentWorks keeps the run
identity, workspace, lifecycle, and durable history; a remote agent cannot choose those values for itself.

## Skills

A skill is verified guidance that can be included in an agent's context. It is content, not permission. Adding a skill
does not grant access to a tool, credential, workspace, or external system.

## Choosing an integration

| Need | Use |
|---|---|
| Call an LLM | Model gateway |
| Run a small application action | Tool |
| Use a standard tool server | MCP |
| Delegate work to another agent | A2A or a remote executor |
| Add reusable instructions or know-how | Skill |

For settings, see [Configuration](reference/configuration.md). For production security, see
[Security operations](security/operations.md).
