#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <base-sha> <head-sha>" >&2
  exit 2
fi

base="$1"
head="$2"
count=0
while IFS= read -r commit; do
  [[ -n "$commit" ]] || continue
  count=$((count + 1))
  if ! git show -s --format=%B "$commit" | git interpret-trailers --parse | grep -qi '^Signed-off-by: .\+ <[^>][^>]*>$'; then
    echo "dco=FAIL unsigned_commit=$commit" >&2
    exit 1
  fi
done < <(git rev-list --no-merges "$base..$head")

if [[ $count -eq 0 ]]; then
  echo "dco=FAIL no contribution commits in $base..$head" >&2
  exit 1
fi
echo "dco=PASS commits=$count"
