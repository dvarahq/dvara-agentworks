#!/usr/bin/env python3
"""Fail closed when the supported packaging loses its pinned or hardened deployment contract."""

from __future__ import annotations

import json
import pathlib
import re
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SHA = r"sha256:[a-f0-9]{64}"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"packaging=FAIL: {message}")


dockerfile = (ROOT / "Dockerfile").read_text()
from_lines = re.findall(r"^FROM\s+.+$", dockerfile, re.MULTILINE)
require(len(from_lines) == 2 and all(re.search(rf"\S+@{SHA}", line) for line in from_lines),
        "every Dockerfile stage must be pinned by digest")
require("USER 65532:65532" in dockerfile, "runtime image must use the non-root uid/gid")
require("-XX:+ExitOnOutOfMemoryError" in dockerfile, "runtime must fail closed on OOM")
require("COPY --chown=65532:65532" in dockerfile, "application artifact ownership must be explicit")
require("COPY --from=kubectl /bin/kubectl" in dockerfile,
        "the supported image must carry the pinned Kubernetes sandbox control binary")
require("ARG JAR_FILE=agentworks-server/target/agentworks-server-${VERSION}.jar" in dockerfile,
        "the runtime image artifact must follow the declared image version")
require("0.0.1-SNAPSHOT" not in dockerfile,
        "the runtime image must not retain the pre-RC snapshot artifact default")

compose_smoke = (ROOT / "scripts/packaging/compose-smoke.sh").read_text()
require("agentworks-server-${project_version}.jar" in compose_smoke,
        "the Compose smoke test must use the reactor versioned server artifact")
require("AGENTWORKS_VERSION=\"$project_version\"" in compose_smoke,
        "the Compose smoke test must pass the reactor version to the image build")

compose = (ROOT / "deploy/compose/compose.yaml").read_text()
require(re.search(rf"image:\s+postgres:17-alpine@{SHA}", compose) is not None,
        "Compose PostgreSQL image must be pinned by digest")
for clause in ("read_only: true", "cap_drop: [ALL]", "no-new-privileges:true", "stop_grace_period: 60s",
               "SPRING_CONFIG_IMPORT: configtree:/run/secrets/"):
    require(clause in compose, f"Compose is missing {clause}")
require("POSTGRES_PASSWORD:" not in compose, "Compose must not contain an inline PostgreSQL password")

chart = ROOT / "deploy/helm/agentworks"
chart_yaml = (chart / "Chart.yaml").read_text()
require('kubeVersion: ">=1.35.0-0 <1.38.0-0"' in chart_yaml,
        "chart must declare the supported Kubernetes 1.35-1.37 window")
schema = json.loads((chart / "values.schema.json").read_text())
digest_pattern = schema["properties"]["image"]["properties"]["digest"]["pattern"]
require("sha256" in digest_pattern and "64" in digest_pattern, "chart image digest schema is missing")

templates = "\n".join(path.read_text() for path in sorted((chart / "templates").glob("*")) if path.is_file())
for clause in ("runAsNonRoot: true", "readOnlyRootFilesystem: true", "capabilities: {drop: [ALL]}",
               "/actuator/health/startup", "/actuator/health/readiness", "/actuator/health/liveness",
               "kind: NetworkPolicy", "kind: PodDisruptionBudget", "resources: [pods/exec]",
               "automountServiceAccountToken: false", "configtree:/run/secrets/agentworks/",
               "kubernetes.io/metadata.name: {{ .Release.Namespace }}"):
    require(clause in templates, f"Helm templates are missing {clause}")
require("kind: Secret" not in templates, "chart must consume, not manufacture, production credentials")
require("secretKeyRef:" not in templates, "database credentials must be mounted files, not environment variables")

helm = shutil.which("helm")
if helm:
    # Prefer the Homebrew-managed binary over a stale manually installed copy on macOS.
    homebrew = pathlib.Path("/opt/homebrew/bin/helm")
    if homebrew.exists():
        helm = str(homebrew)
    digest = "sha256:" + "a" * 64
    subprocess.run([helm, "lint", str(chart), "--set", f"image.digest={digest}"], check=True,
                   stdout=subprocess.DEVNULL)
    for version in ("1.35.9", "1.36.5", "1.37.1"):
        rendered = subprocess.run([helm, "template", "agentworks", str(chart), "--kube-version", version,
                                   "--set", f"image.digest={digest}"], check=True, text=True,
                                  stdout=subprocess.PIPE).stdout
        require("kind: Deployment" in rendered and "kind: NetworkPolicy" in rendered,
                f"chart did not render its workload for Kubernetes {version}")
else:
    raise SystemExit("packaging=FAIL: helm is required to validate the supported chart")

print("packaging=PASS image=distroless compose=postgres17 kubernetes=1.35-1.37")
