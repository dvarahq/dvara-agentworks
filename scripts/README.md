# Helper scripts

This folder contains small programs used to build, test, package, release, and operate AgentWorks. They are not
agents, and AgentWorks does not run them in the background.

If you only want to try AgentWorks, you can ignore this folder and follow the
[getting-started guide](../docs/getting-started.md).

## Common commands

Run these from the repository root:

| What you want to do | Command |
|---|---|
| Run the quick test suite | `scripts/test/fast.sh` |
| Run tests that use PostgreSQL | `scripts/test/postgres.sh` |
| Run the broader integration suite | `scripts/test/integration.sh` |
| Build the command-line client | `scripts/build-cli.sh` |

The first Maven or container run may download dependencies. Later runs reuse the local caches.

## What each area is for

| Path | Purpose | Main audience |
|---|---|---|
| `test/` | Convenient entry points for fast, PostgreSQL, and integration tests | Contributors |
| `ci/` | Documentation, security, licensing, compatibility, and repository checks used by pull requests | Maintainers and GitHub Actions |
| `packaging/` | Verifies the Docker Compose and Kubernetes packages | Maintainers and operators |
| `postgresql/` | Database role setup, backup, restore, retention, and recovery drills | Database and platform operators |
| `release/` | Builds and verifies release candidates, charts, evidence, and public releases | Release maintainers |

Files directly in this folder provide a few focused tools:

- `agentworks-eval` runs an evaluation;
- `agentworks-replay` replays recorded agent activity;
- `agentworks-artifacts` works with stored artifacts;
- `performance-qualification.sh` runs the performance qualification;
- `fake_external_runner.py` provides a local external-runner substitute for tests;
- `verify-public-tree.sh` checks that the public repository contains only its approved files.

## Why these scripts are in the repository

Keeping the scripts beside the source makes the project's tests, packages, and releases reproducible. You can inspect
the same commands that the project uses instead of relying on hidden build or release machinery.

Some scripts start containers, create temporary data, or expect operator configuration. Read the script and its
linked guide before running an unfamiliar operational or release command.
