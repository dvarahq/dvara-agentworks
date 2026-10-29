# Upgrade without losing control

An AgentWorks upgrade changes both application code and, sometimes, the database. Make the database change once,
then introduce the new application gradually.

## Before the upgrade

1. Check that the old and new versions fit the [compatibility policy](compatibility.md).
2. Take a verified backup and record the current image digest and deployment settings.
3. Run `flyway validate` and rehearse the upgrade in a production-like environment.
4. If old and new workers will overlap, test that mixed-version window explicitly.
5. Keep the previous compatible image ready.

Never edit a migration that has appeared in a release.

## Roll forward

1. Apply the database migration once, using the migration owner rather than an application login.
2. Start one new canary instance pinned to the exact image digest.
3. Check readiness, authentication, tenant isolation, a new run, an existing continuation, event streaming, and
   telemetry.
4. Add new instances gradually while watching errors, latency, backlog, recoveries, and PostgreSQL.
5. Keep the old compatible image and integrations available until older runs have drained.

## If the application must roll back

Use `helm rollback <release> <revision>` or deploy the previous image digest. This restores application capacity; it
does not reverse database migrations or rewrite runs already in progress.

When a committed database change needs correction, use forward recovery: add a new migration that repairs the
problem. Use point-in-time recovery only when that cannot preserve correctness, because recovery can discard newer
data.

After either path, check tenant isolation, durable events, idempotency, callbacks, schedules, and representative old
and new runs before accepting normal traffic.
