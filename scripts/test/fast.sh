#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# A hosted runner starts from a clean checkout, so repeating Maven's clean phase only spends time deleting empty
# targets. The test phase compiles every production and test source and runs the complete fast-test partition while
# leaving packaging, source jars, Javadocs, and release verification to explicit qualification.
exec "$root/mvnw" -B -ntp -T 1C -Pfast-tests test "$@"
