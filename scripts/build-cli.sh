#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
destination="${1:-$repo_root/dist}"
staging="$(mktemp -d)"
trap 'rm -rf "$staging"' EXIT
mkdir -p "$staging/agentworks"
cp "$repo_root"/cli/agentworks/*.py "$staging/agentworks/"
# Supplying a root launcher avoids zipapp generating one with the current timestamp.
cp "$repo_root/cli/zipapp_main.py" "$staging/__main__.py"
# zipapp records source mtimes. A fixed ZIP-compatible epoch makes repeated builds byte-for-byte reproducible.
find "$staging" -exec touch -t 198001010000 {} +
mkdir -p "$destination"
python3 -m zipapp "$staging" --python '/usr/bin/env python3' \
  --output "$destination/agentworks"
chmod 0755 "$destination/agentworks"
"$destination/agentworks" --version
