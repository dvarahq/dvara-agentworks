#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "usage: $0 VERIFIED_BACKUP_DIRECTORY EMPTY_RESTORE_DIRECTORY [RECOVERY_TARGET_TIME]" >&2
  exit 64
fi
backup_dir=$1
restore_dir=$2
target_time=${3:-}
if [[ "$target_time" == *"'"* || "$target_time" == *$'\n'* || "$target_time" == *$'\r'* ]]; then
  echo "recovery target contains an unsupported character" >&2
  exit 64
fi

[[ -f "$backup_dir/backup_manifest" ]] || { echo "missing backup_manifest" >&2; exit 65; }
if [[ -e "$restore_dir" && ( ! -d "$restore_dir" || -n "$(find "$restore_dir" -mindepth 1 -maxdepth 1 -print -quit)" ) ]]; then
  echo "restore destination must be absent or an empty directory: $restore_dir" >&2
  exit 64
fi
pg_verifybackup "$backup_dir"
mkdir -p "$restore_dir"
cp -a "$backup_dir"/. "$restore_dir"/
touch "$restore_dir/recovery.signal"
if [[ -n "$target_time" ]]; then
  printf "recovery_target_time = '%s'\nrecovery_target_action = 'promote'\n" \
    "$target_time" >> "$restore_dir/postgresql.auto.conf"
fi
chmod 0700 "$restore_dir"
echo "Prepared $restore_dir. Start an isolated matching PostgreSQL major version and run the integrity queries in docs/operations/postgresql-production.md."
