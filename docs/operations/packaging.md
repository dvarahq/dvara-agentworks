# Run AgentWorks with Compose or Kubernetes

AgentWorks is one Java application backed by PostgreSQL. You can run the JAR directly, use Docker Compose for local
evaluation, or install the Helm chart on Kubernetes.

## Local evaluation with Docker Compose

The [getting-started guide](../getting-started.md) prepares the password and builds the application. Start it with:

```bash
docker compose -f deploy/compose/compose.yaml up --build -d
curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

Stop it and remove the evaluation data with:

```bash
docker compose -f deploy/compose/compose.yaml down --volumes
```

This setup is hardened enough for local evaluation, but it uses one database owner. It is not the production
topology.

## Container image

The supplied image runs as a fixed non-root user with a read-only root filesystem. It contains the AgentWorks server
and the small runtime tools it needs—no source tree, package manager, shell, or credentials.

Use an exact image digest in production. Supported image platforms are `linux/amd64` and `linux/arm64`.

## Kubernetes with Helm

The chart expects an existing PostgreSQL database and a Kubernetes Secret containing three database identities:

- migration owner;
- Control Plane user;
- Run Plane user.

Set an exact image digest, then install:

```bash
helm install agentworks deploy/helm/agentworks \
  --namespace agentworks --create-namespace \
  --set image.repository=ghcr.io/dvarahq/dvara-agentworks \
  --set image.digest="$IMAGE_DIGEST"
```

Wait for readiness:

```bash
kubectl -n agentworks rollout status deployment/agentworks-agentworks
```

The chart supplies non-root containers, a read-only root filesystem, resource limits, health probes, graceful
shutdown, a disruption budget, and a baseline NetworkPolicy. Narrow network egress to your database, model providers,
MCP/A2A services, DNS, and telemetry endpoints.

## Upgrades

Use `helm upgrade` with a new digest and watch one canary before completing the rollout. Keep the previous compatible
image available while old runs are still draining. `helm rollback` restores application capacity; it does not undo
database migrations.

## Secrets

Infrastructure secrets belong in Docker or Kubernetes secrets. Agent definitions contain only credential references.
Do not put real secret values in an AgentSpec, image, Helm values, logs, or support bundle.

## Sandboxed tools

The optional Kubernetes sandbox creates short-lived, non-root Pods with no API token and deny-by-default networking.
It supports CPU, memory, time, and output limits. It does not claim portable PID limits, filesystem snapshots, or
durable Pod restore; an agent that requires an unsupported capability is refused before execution.

Before production, complete the [Production checklist](production.md) and [PostgreSQL guide](postgresql-production.md).
