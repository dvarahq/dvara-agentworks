#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 KIND_NODE_IMAGE AGENTWORKS_IMAGE_WITH_DIGEST" >&2
  exit 64
fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
node_image="$1"
agentworks_image="$2"
if [[ ! "$node_image" =~ @sha256:[a-f0-9]{64}$ ]] || [[ ! "$agentworks_image" =~ @sha256:[a-f0-9]{64}$ ]]; then
  echo "both node and AgentWorks images must be pinned by sha256 digest" >&2
  exit 64
fi
cluster="agentworks-qualification-${RANDOM}"
namespace="agentworks-test"
release="agentworks"

cleanup() {
  kind delete cluster --name "$cluster" >/dev/null 2>&1 || true
}
trap cleanup EXIT

kind create cluster --name "$cluster" --image "$node_image" --wait 120s
kubectl create namespace "$namespace"
kubectl --namespace "$namespace" create secret generic agentworks-database \
  --from-literal=migration-url=jdbc:postgresql://postgres:5432/agentworks \
  --from-literal=migration-username=agentworks --from-literal=migration-password=qualification-only \
  --from-literal=control-plane-url=jdbc:postgresql://postgres:5432/agentworks \
  --from-literal=control-plane-username=agentworks --from-literal=control-plane-password=qualification-only \
  --from-literal=run-plane-url=jdbc:postgresql://postgres:5432/agentworks \
  --from-literal=run-plane-username=agentworks --from-literal=run-plane-password=qualification-only
kubectl --namespace "$namespace" create secret generic postgres-password --from-literal=password=qualification-only
kubectl --namespace "$namespace" apply -f - <<'YAML'
apiVersion: apps/v1
kind: Deployment
metadata: {name: postgres}
spec:
  replicas: 1
  selector: {matchLabels: {app: postgres}}
  template:
    metadata: {labels: {app: postgres}}
    spec:
      containers:
        - name: postgres
          image: postgres:17-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24
          env:
            - {name: POSTGRES_DB, value: agentworks}
            - {name: POSTGRES_USER, value: agentworks}
            - name: POSTGRES_PASSWORD
              valueFrom: {secretKeyRef: {name: postgres-password, key: password}}
          readinessProbe: {exec: {command: [pg_isready, -U, agentworks]}, periodSeconds: 2}
---
apiVersion: v1
kind: Service
metadata: {name: postgres}
spec: {selector: {app: postgres}, ports: [{port: 5432, targetPort: 5432}]}
YAML
kubectl --namespace "$namespace" rollout status deployment/postgres --timeout=120s

repository="${agentworks_image%@*}"
digest="${agentworks_image#*@}"
helm install "$release" "$root/deploy/helm/agentworks" --namespace "$namespace" \
  --set image.repository="$repository" --set image.digest="$digest" \
  --set replicaCount=1
kubectl --namespace "$namespace" rollout status deployment/agentworks-agentworks --timeout=180s

helm upgrade "$release" "$root/deploy/helm/agentworks" --namespace "$namespace" \
  --set image.repository="$repository" --set image.digest="$digest" \
  --set replicaCount=1 \
  --set runtime.buildId=qualification-upgrade
kubectl --namespace "$namespace" rollout status deployment/agentworks-agentworks --timeout=180s
helm rollback "$release" 1 --namespace "$namespace" --wait --timeout 180s

pod="$(kubectl --namespace "$namespace" get pods -l app.kubernetes.io/name=agentworks -o jsonpath='{.items[0].metadata.name}')"
kubectl --namespace "$namespace" delete pod "$pod" --wait=true
kubectl --namespace "$namespace" rollout status deployment/agentworks-agentworks --timeout=180s

helm uninstall "$release" --namespace "$namespace" --wait
test -z "$(kubectl --namespace "$namespace" get all,networkpolicy,role,rolebinding -l app.kubernetes.io/instance="$release" -o name)"
echo "kind-qualification=PASS node=$node_image image=$agentworks_image"
