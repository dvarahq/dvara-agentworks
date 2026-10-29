#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
compose="$root/deploy/compose/compose.yaml"
project="agentworks-smoke-$$"
work="$(mktemp -d)"
password_file="$work/postgres-password"
port="${AGENTWORKS_SMOKE_PORT:-18080}"
project_version="$(python3 -c 'import xml.etree.ElementTree as E, sys; r=E.parse(sys.argv[1]).getroot(); print(r.findtext("{http://maven.apache.org/POM/4.0.0}version"))' "$root/pom.xml")"
server_jar="$root/agentworks-server/target/agentworks-server-${project_version}.jar"

cleanup() {
  docker compose --project-name "$project" --file "$compose" down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -f "$password_file"
  rmdir "$work" >/dev/null 2>&1 || true
}
trap cleanup EXIT

umask 077
openssl rand -hex 24 >"$password_file"

if [[ ! -f "$server_jar" ]]; then
  "$root/mvnw" -B -ntp -DskipTests package -pl agentworks-server -am
fi

AGENTWORKS_VERSION="$project_version" AGENTWORKS_REVISION="$(git -C "$root" rev-parse HEAD)" AGENTWORKS_PORT="$port" \
  POSTGRES_PASSWORD_FILE="$password_file" docker compose --project-name "$project" --file "$compose" \
  up --build --detach

for _ in {1..90}; do
  if curl --fail --silent --show-error "http://127.0.0.1:$port/actuator/health/readiness" >/dev/null; then
    break
  fi
  sleep 2
done
curl --fail --silent --show-error "http://127.0.0.1:$port/actuator/health/readiness" | grep -q '"status":"UP"'

container="$(docker compose --project-name "$project" --file "$compose" ps --quiet agentworks)"
test "$(docker inspect --format '{{.Config.User}}' "$container")" = "65532:65532"
test "$(docker inspect --format '{{.HostConfig.ReadonlyRootfs}}' "$container")" = "true"
test "$(docker inspect --format '{{.State.Status}}' "$container")" = "running"

echo "compose-smoke=PASS image=$(docker inspect --format '{{.Image}}' "$container")"
