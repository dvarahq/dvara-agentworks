# AgentWorks command line

The `agentworks` command lets you manage agents, releases, deployments, and runs from a terminal or script. It uses
the same public API as the Web Console—there is no hidden administrator back door.

## Install

Python 3.11 or newer is required.

```bash
scripts/build-cli.sh dist
install -m 0755 dist/agentworks "$HOME/.local/bin/agentworks"
agentworks --version
```

## Connect to a server

Create a profile that points to AgentWorks. Store the token in an environment variable or owner-only file; the CLI
does not save the token itself.

```bash
agentworks profile set local --server http://127.0.0.1:8080 --token-env AGENTWORKS_TOKEN
agentworks profile use local
export AGENTWORKS_TOKEN='awt_...'
agentworks auth status
```

`AGENTWORKS_URL`, `AGENTWORKS_PROFILE`, and `AGENTWORKS_TOKEN` can temporarily override the active profile.

## Everyday commands

```bash
# See agents and runs
agentworks agent list
agentworks run list

# Inspect one run and follow its events
agentworks run show RUN_ID
agentworks stream --run-id RUN_ID

# Get the live API description
agentworks diagnostic openapi
```

Run `agentworks <group> <action> --help` for the arguments of any command.

## Automation

Use `--output json` for scripts. Every command prints one JSON object; streams print one object per event. A successful
result has exit code `0`. Invalid input, authentication failures, API refusals, network failures, and domain failures
use different nonzero codes so scripts can respond safely.

Write operations may require:

- `--idempotency-key` to prevent accidental duplicate work;
- `--if-match` with the latest ETag to prevent overwriting a newer change;
- `--file request.json` for a JSON request body.

The CLI redacts token-like values from output. It retries safe reads after temporary failures. It retries a mutation
only when the API says the operation is idempotent or you supplied an idempotency key.

## Complete command index

This list is checked against the CLI implementation.

<!-- agentworks-cli-command-index:start -->
```text
agent create
agent list
agent publish
agent show
agent versions
artifact download
artifact expire
artifact list
artifact pin
artifact pins
artifact show
artifact unpin
artifact upload
artifact validate
audit list
auth status
context show
deploy cancel
deploy cohort
deploy continue-waiting
deploy list
deploy migrate
deploy purge
deploy quiescence
deploy resume
deploy retained-cost
deploy retention
deploy retire
deploy revoke
deploy rollback
deploy routing
deploy show
deploy suspend
diagnostic context
diagnostic openapi
eval baselines
eval cancel
eval compare
eval create-baseline
eval create-dataset
eval create-evaluator
eval datasets
eval evaluators
eval purge-expired
eval report
eval resume
eval run
eval status
events list
interaction cancel
interaction expire
interaction list
interaction respond
interaction revoke
interaction show
operation cancel
operation list
operation show
profile list
profile remove
profile set
profile use
promote apply
promote approval
promote approval-show
promote approve
release build
release lifecycle
release manifest
release resume
release retire
release revocations
release revoke
release show
release suspend
release verify
replay plan
replay start
run cancel
run list
run pause
run resume
run show
run start
simulate run
stream
test run
```
<!-- agentworks-cli-command-index:end -->

For API behavior and error formats, see the [API guide](reference/api.md).
