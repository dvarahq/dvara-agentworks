# Run PostgreSQL in production

AgentWorks stores its durable state in PostgreSQL. It uses ordinary Spring JDBC transactions; it does not require
JPA, JTA, or coordination between multiple transaction managers.

## Use three database identities

| Identity | What it does |
|---|---|
| Migration owner | Applies schema changes. It is not an application login. |
| Control Plane user | Manages agents, releases, deployments, and other desired state. |
| Run Plane user | Claims and updates running work. |

Do not run the application as the migration owner. Separate roles limit the damage from a leaked credential or a
programming mistake. Row-level security (RLS) adds another tenant-isolation boundary inside PostgreSQL.

## Before the first production start

1. Create the plane roles and separate login users.
2. Put their passwords in your deployment secret manager.
3. Run Flyway with the migration owner.
4. Start one AgentWorks instance and check readiness.
5. Confirm two test workspaces cannot see each other's data.

The supplied provisioning SQL can create the standard roles. If your deployment account cannot create roles, ask a
DBA to create them before Flyway runs.

## Connections and capacity

Set connection-pool sizes from the real database connection budget. Leave room for migrations, monitoring, backups,
and emergency access. More application replicas do not help if their combined pools exhaust PostgreSQL.

Watch connection use, slow queries, locks, storage, WAL growth, and replication lag.

## Backups and recovery

Use continuous WAL archiving plus regular base backups so point-in-time recovery is possible. Encrypt backups,
restrict access, and rehearse restoration on a schedule. Keep the restore procedure available when the primary
system is unavailable.

Choose an RPO and RTO for your service, then size backup frequency and staffing accordingly. A recovery plan is not
proven until a restore has succeeded and the recovered data has been checked.

## Safe schema upgrades

Before upgrading, take a verified backup and run `flyway validate`. Apply migrations once with the migration owner,
then start a canary application instance. Never edit or delete an already released migration.

If a new application cannot start after a migration, prefer forward recovery with a new corrective migration.
Point-in-time recovery is the last resort because it can discard newer data.

See [Upgrade and rollback](upgrade-and-rollback.md) for the full flow and [Compatibility](compatibility.md) for
supported PostgreSQL versions.
