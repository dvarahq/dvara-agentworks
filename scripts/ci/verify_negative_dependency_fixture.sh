#!/usr/bin/env bash
set -euo pipefail

log="${TMPDIR:-/tmp}/agentworks-negative-dependency-fixture.log"
if ./mvnw -B -ntp validate -Parchitecture-negative-fixture >"$log" 2>&1; then
  echo "negative-dependency-fixture=FAIL: injected Redis dependency was accepted" >&2
  exit 1
fi
if ! grep -q 'spring-boot-starter-data-redis' "$log"; then
  cat "$log" >&2
  echo "negative-dependency-fixture=FAIL: build failed for an unexpected reason" >&2
  exit 1
fi
echo "negative-dependency-fixture=PASS"
