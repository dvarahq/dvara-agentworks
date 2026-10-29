#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <owner/repository> <branch>" >&2
  exit 2
fi

repo="$1"
branch="$2"
root="$(cd "$(dirname "$0")/../.." && pwd)"
gh api --method PUT "repos/$repo/branches/$branch/protection" \
  --input "$root/config/branch-protection.json"
