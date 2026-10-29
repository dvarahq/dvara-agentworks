#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec "$root/mvnw" -B -ntp -T 1C -Pintegration-tests test "$@"
