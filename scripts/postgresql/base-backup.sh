#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 EMPTY_BACKUP_DIRECTORY" >&2
  exit 64
fi
backup_dir=$1
if [[ -e "$backup_dir" && ( ! -d "$backup_dir" || -n "$(find "$backup_dir" -mindepth 1 -maxdepth 1 -print -quit)" ) ]]; then
  echo "backup destination must be absent or an empty directory: $backup_dir" >&2
  exit 64
fi
mkdir -p "$backup_dir"

: "${PGHOST:?set PGHOST}"
: "${PGUSER:?set a REPLICATION-capable PGUSER}"
: "${PGDATABASE:=postgres}"

start_epoch=$(date +%s)
pg_basebackup --pgdata="$backup_dir" --format=plain --wal-method=stream --checkpoint=fast \
  --manifest-checksums=SHA256 --progress
pg_verifybackup "$backup_dir"
end_epoch=$(date +%s)

printf '{"kind":"agentworks-postgresql-base-backup","startedEpoch":%s,"completedEpoch":%s,"verified":true}\n' \
  "$start_epoch" "$end_epoch" > "${backup_dir%/}.evidence.json"
