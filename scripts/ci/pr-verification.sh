#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${BASE_SHA:-}" || -n "${HEAD_SHA:-}" ]]; then
    if [[ -z "${BASE_SHA:-}" || -z "${HEAD_SHA:-}" ]]; then
        echo "BASE_SHA and HEAD_SHA must either both be set or both be empty" >&2
        exit 2
    fi
    scripts/ci/verify_dco.sh "$BASE_SHA" "$HEAD_SHA"
else
    echo "dco=SKIP non-pull-request event"
fi

# Keep the required hosted check below two minutes. Full Java, PostgreSQL, browser, packaging, and performance
# validation is completed locally before the single push.
scripts/verify-public-tree.sh
python3 scripts/ci/verify_repository_policy.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s src/test/python -v
