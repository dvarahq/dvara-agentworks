#!/usr/bin/env bash
set -euo pipefail

command -v docker >/dev/null || { echo "docker is required" >&2; exit 69; }
drill_id="agentworks-pitr-$$"
primary="${drill_id}-primary"
restored="${drill_id}-restored"
network="${drill_id}-network"
archive="${drill_id}-archive"
backup="${drill_id}-backup"
restore="${drill_id}-restore"
password="drill-only-password"

cleanup() {
  status=$?
  trap - EXIT INT TERM
  if [[ "$status" -ne 0 ]]; then
    docker logs "$primary" 2>/dev/null || true
    docker logs "$restored" 2>/dev/null || true
  fi
  docker rm -f "$primary" "$restored" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  docker volume rm "$archive" "$backup" "$restore" >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT INT TERM

docker network create "$network" >/dev/null
docker volume create "$archive" >/dev/null
docker volume create "$backup" >/dev/null
docker volume create "$restore" >/dev/null
docker run --rm -v "$archive:/archive" -v "$backup:/backup" postgres:17-alpine \
  sh -c 'chown postgres:postgres /archive /backup'
start_epoch=$(date +%s)

docker run -d --name "$primary" --network "$network" \
  -e POSTGRES_PASSWORD="$password" -e POSTGRES_DB=agentworks \
  -v "$archive:/archive" -v "$backup:/backup" postgres:17-alpine \
  postgres -c wal_level=replica -c archive_mode=on -c archive_timeout=1s \
  -c "archive_command=test ! -f /archive/%f && cp %p /archive/%f" >/dev/null

for _ in $(seq 1 60); do
  docker exec "$primary" pg_isready -U postgres -d agentworks >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$primary" pg_isready -U postgres -d agentworks >/dev/null
docker exec -e PGPASSWORD="$password" "$primary" psql -v ON_ERROR_STOP=1 -U postgres -d agentworks \
  -c "create table pitr_marker(id integer primary key, label text not null)" >/dev/null
docker exec -e PGPASSWORD="$password" "$primary" pg_basebackup -h 127.0.0.1 -U postgres \
  -D /backup/base -Fp -Xs -c fast --manifest-checksums=SHA256 >/dev/null
docker exec "$primary" pg_verifybackup /backup/base >/dev/null

docker exec -e PGPASSWORD="$password" "$primary" psql -v ON_ERROR_STOP=1 -U postgres -d agentworks \
  -c "insert into pitr_marker values (1, 'before-target')" >/dev/null
target_time=$(docker exec -e PGPASSWORD="$password" "$primary" psql -At -U postgres -d agentworks \
  -c "select clock_timestamp()")
sleep 1
docker exec -e PGPASSWORD="$password" "$primary" psql -v ON_ERROR_STOP=1 -U postgres -d agentworks \
  -c "insert into pitr_marker values (2, 'after-target'); select pg_switch_wal()" >/dev/null
sleep 3
docker stop "$primary" >/dev/null

docker run --rm -e TARGET_TIME="$target_time" -v "$backup:/backup:ro" -v "$restore:/restore" \
  postgres:17-alpine sh -ceu '
    cp -a /backup/base/. /restore/
    chown -R postgres:postgres /restore
    touch /restore/recovery.signal
    printf "restore_command = '\''cp /archive/%%f %%p'\''\nrecovery_target_time = '\''%s'\''\nrecovery_target_action = '\''promote'\''\n" "$TARGET_TIME" >> /restore/postgresql.auto.conf
  '

docker run -d --name "$restored" --network "$network" -e POSTGRES_PASSWORD="$password" \
  -v "$restore:/var/lib/postgresql/data" -v "$archive:/archive:ro" postgres:17-alpine >/dev/null
for _ in $(seq 1 60); do
  docker exec "$restored" pg_isready -U postgres -d agentworks >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$restored" pg_isready -U postgres -d agentworks >/dev/null
rows=$(docker exec -e PGPASSWORD="$password" "$restored" psql -At -U postgres -d agentworks \
  -c "select string_agg(id || ':' || label, ',' order by id) from pitr_marker")
[[ "$rows" == "1:before-target" ]] || { echo "PITR verification failed: $rows" >&2; exit 1; }

end_epoch=$(date +%s)
rto_seconds=$((end_epoch - start_epoch))
[[ "$rto_seconds" -le 3600 ]] || { echo "PITR drill exceeded the 3600-second RTO" >&2; exit 1; }
printf '{"kind":"agentworks-postgresql-pitr-drill","postgres":"17-alpine","targetTime":"%s","restored":"%s","rtoSeconds":%s,"result":"PASS"}\n' \
  "$target_time" "$rows" "$rto_seconds"
