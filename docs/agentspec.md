# Write an AgentSpec

An AgentSpec is a YAML file that describes an agent. Think of it as the agent's recipe: which runtime it uses, which
models it can call, what instructions it follows, and what limits apply.

The canonical JSON Schema is the complete field reference and is also used to validate every AgentSpec.

The smallest useful example is:

```yaml
apiVersion: dvarahq.com/v1alpha1
kind: Agent
metadata:
  name: refund-agent
  version: 0.1.0
spec:
  executor:
    type: remote-http
```

Save a new version whenever you change the recipe. Published versions are immutable, so a running agent never changes
behind your back.

## A few important rules

- Give each version a clear version number.
- Refer to models by configured aliases instead of hard-coding provider details.
- Use a **credential reference**; never put an API key or password in YAML.
- Pin sandbox images by digest when you use a sandbox.
- Set limits for runtime, model calls, tool calls, tokens, and cost.
- Keep the file to one plain YAML document; advanced YAML aliases and tags are intentionally rejected.

AgentWorks validates the file against its canonical JSON Schema before publishing it. Unknown or unsafe fields are
rejected with a clear error.

Use `agentworks agent create` to create the agent and `agentworks agent publish` to publish a version. See the
[CLI guide](cli.md) or [API guide](reference/api.md) for the request details.
