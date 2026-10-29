#!/usr/bin/env python3
"""Fail closed when the operator telemetry, exporter profiles, dashboard, or alert contract drifts."""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def require(text: str, values: list[str], owner: str) -> None:
    missing = [value for value in values if value not in text]
    if missing:
        raise SystemExit(f"observability=FAIL {owner} missing {', '.join(missing)}")


docs = (ROOT / "docs/operations/observability.md").read_text()
prometheus = (ROOT / "agentworks-server/src/main/resources/application-prometheus.yaml").read_text()
otlp = (ROOT / "agentworks-server/src/main/resources/application-otlp.yaml").read_text()
defaults = (ROOT / "agentworks-server/src/main/resources/application.yaml").read_text()
alerts = (ROOT / "deploy/observability/prometheus/agentworks-alerts.yaml").read_text()
dashboard_path = ROOT / "deploy/observability/grafana/agentworks-overview.json"
dashboard = dashboard_path.read_text()
json.loads(dashboard)

metrics = [
    "agentworks.run.worker.polls", "agentworks.run.lease.losses", "agentworks.run.recovery.delay",
    "agentworks.recovery.outbox.backlog", "agentworks.recovery.outbox.oldest.age",
    "agentworks.compiled.cache.retirement.lag", "agentworks.resource.cleanup.unresolved.at.startup",
]
require(docs, metrics + ["cardinality", "support-bundle", "required-dependencies"], "documentation")
require(prometheus, ["include: health,info,prometheus", "prometheus:", "enabled: true", "otlp:"], "Prometheus profile")
require(otlp, ["prometheus:", "enabled: false", "otlp:", "enabled: true", "127.0.0.1:4318"], "OTLP profile")
require(defaults, ["http.server.requests: true", "agentworks.run.recovery.delay: true",
                   "agentworks.run.worker.poll.duration: true", "include: readinessState,agentWorksRequiredDependencies",
                   "include: agentWorksRequiredDependencies"], "histogram and health policy")
require(alerts, ["ApiErrorRate", "ApiLatency", "WorkerSaturated", "LeaseLoss", "RecoveryLag", "OutboxLag", "ResourceLeak"], "alerts")
require(dashboard, ["API request rate", "API 5xx rate", "API p95 latency", "Worker polls and saturation",
                    "Lease loss and recovery lag", "Recovery outbox", "Resource cleanup"], "dashboard")
print("observability=PASS profiles=2 dashboard=1 alert-domains=7")
