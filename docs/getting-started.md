# Create, test, and deploy your first agent

This guide walks through the complete life of one agent. You will describe it, test it locally, deploy the tested
version, run it, stop AgentWorks, and prove that the work survives a restart.

You do not need a paid model or an API key. The walkthrough uses a predictable local test model, so you can focus on
learning AgentWorks. It does not run a hidden demo: you create every object yourself in the Web Console.

By the end, you will have:

1. started AgentWorks and its PostgreSQL database;
2. described an agent in a small YAML document;
3. locked that definition into a release;
4. deployed and tested the release in `development`;
5. promoted the same tested release to `production`;
6. stopped and restarted AgentWorks without losing its agents or queued work.

Plan for about 15 minutes after the first Maven and Docker downloads complete.

## The whole journey at a glance

```text
Describe       Lock             Deploy             Run
the agent  ->  a release   ->   to development ->  and inspect it
   YAML         exact version    logical route       durable job
                                      |
                                      v
                              promote the same
                              release to production
```

AgentWorks is the running server around this journey. PostgreSQL remembers the definitions, releases, deployments,
runs, and history. A worker takes queued runs and carries out the agent's instructions using configured models,
tools, or other agents.

For this walkthrough, `development` and `production` are logical environments inside the same local installation.
Deploying selects which release receives new work; it does not create a cloud server or Kubernetes cluster.

## Do I need to write code?

Not for this simple agent. You describe its instructions, model, and limits in YAML through the Web Console. The YAML
is configuration, not executable program code. More advanced agents can call existing HTTP tools, MCP servers, and
other agents. You write integration code only when you need to create a new custom tool or adapter.

## What the words mean

| Word | Plain meaning |
|---|---|
| **Agent definition (AgentSpec)** | The YAML description of the agent's instructions, models, tools, and limits. |
| **Published version** | A validated definition that can no longer be edited. A change becomes a new version. |
| **Release** | The exact agent version and dependencies that you test and promote together. It cannot change underneath a running agent. |
| **Environment** | A logical stage such as `development` or `production`. |
| **Deployment** | A stable name in an environment that sends new runs to a chosen release. |
| **Run** | One job performed by a deployed agent for one input. |

## 1. Check the prerequisites

You need macOS or Linux, JDK 25, Docker, Git, and `curl`.

```bash
java -version
docker version
curl --version
```

Run the remaining commands from the repository root.

## 2. Start PostgreSQL

Open your first terminal and start a local database on port `55432`:

```bash
docker run --name agentworks-postgres \
  -e POSTGRES_DB=agentworks \
  -e POSTGRES_USER=agentworks \
  -e POSTGRES_PASSWORD=agentworks-local \
  -p 127.0.0.1:55432:5432 \
  -d postgres:17-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24
```

PostgreSQL holds the durable state. Stopping AgentWorks will not remove this container or its data.

## 3. Build and start AgentWorks

Keep the first terminal for commands. Build the server there:

```bash
./mvnw -B -ntp -DskipTests package -pl agentworks-server -am
```

The build should finish with `BUILD SUCCESS`.

Open a second terminal and start AgentWorks there. Leave this terminal running so you can see the server log:

```bash
java -jar agentworks-server/target/agentworks-server-1.0.0-rc5.jar \
  --spring.profiles.active=local \
  --server.port=8080 \
  --spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/agentworks \
  --spring.datasource.username=agentworks \
  --spring.datasource.password=agentworks-local \
  --agentworks.security.bootstrap.token-file=target/agentworks-demo/bootstrap-token \
  --agentworks.runs.worker.enabled=true \
  --agentworks.gateway.gateways.local.type=deterministic-fake \
  '--agentworks.gateway.gateways.local.models[0].alias=demo-model' \
  '--agentworks.gateway.gateways.local.models[0].model=demo-model'
```

The long command is only the local setup written out explicitly. It tells AgentWorks:

- AgentWorks uses the PostgreSQL container you started;
- a worker is enabled, so queued runs can execute;
- the model gateway is named `local`;
- the agent-facing model alias is `demo-model`;
- to use a predictable test model instead of a paid provider.

Wait until the log says AgentWorks started. Check it from the first terminal:

```bash
curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

## 4. Create the first administrator

AgentWorks wrote a one-time bootstrap token to the file named in the start command. Consume it once:

```bash
BOOTSTRAP_TOKEN="$(tr -d '\n' < target/agentworks-demo/bootstrap-token)"

curl --fail-with-body \
  -H 'Content-Type: application/json' \
  -d "{\"token\":\"$BOOTSTRAP_TOKEN\",\"username\":\"admin\",\"displayName\":\"Local Administrator\",\"password\":\"agentworks-local-demo-2026\"}" \
  http://127.0.0.1:8080/api/v1/auth/bootstrap
```

Open [http://127.0.0.1:8080/console](http://127.0.0.1:8080/console) and sign in with:

- username: `admin`
- password: `agentworks-local-demo-2026`

Use a different password outside this disposable local walkthrough.

## 5. Create the agent

In the Console:

1. Open **Agents**.
2. Under **Create an agent**, enter `hello-agent`.
3. Enter `A small agent used to learn AgentWorks` as the description.
4. Select **Create**.

You have created a named place for the agent. You have not created its behavior yet; that comes from the YAML
definition in the next step.

## 6. Describe and publish the agent

On the `hello-agent` page, replace the **AgentSpec** editor with the YAML below. In plain language, it says: use the
Spring AI executor, follow two short instructions, use the local test model, and stop before the run becomes too
large or too long.

```yaml
apiVersion: dvarahq.com/v1alpha1
kind: Agent
metadata:
  name: hello-agent
  version: 1.0.0
spec:
  executor:
    type: spring-ai
  instructions:
    system: |
      You are a concise assistant.
      Answer in one short sentence.
  models:
    reasoning:
      gateway: local
      model: demo-model
  limits:
    maxSteps: 4
    maxRuntime: 1m
    maxToolCalls: 0
```

Select **Publish**.

Publishing checks the YAML and saves version `1.0.0`. That version is now read-only, so the exact definition can be
identified later. Publishing does not deploy or run the agent. To change it, copy the definition, change its version
to `1.0.1`, and publish the new version.

## 7. Lock the version into a release

The published version now appears in the **Versions** table.

1. Keep the suggested release name `hello-agent-1.0.0`.
2. Select **Build**. Here, **Build** means “create a release”; it does not compile Java again.

The release locks together the published agent version and the exact model choice. This is the object you test and
promote. Its contents cannot silently change between development and production.

## 8. Deploy it to a test environment

Open **Deployments**.

1. Under **Environments**, create an environment named `development`.
2. Under **Promote a release**, choose `development`.
3. Enter `hello` as the deployment name.
4. Choose release `hello-agent-1.0.0`.
5. Select **Promote**.

This is the deployment step. AgentWorks has created a stable entry point named `hello` in `development`. New work
sent there will use release `hello-agent-1.0.0`. Nothing has run yet, and AgentWorks has not created a Docker
container, VM, or Kubernetes cluster.

## 9. Test it in development

### Where the test runs

This is a manual functional test in the Web Console on your computer. The run goes through:

```text
Web Console -> development/hello -> AgentWorks worker -> local test model
```

It uses the `hello` deployment you created in the logical `development` environment. AgentWorks and PostgreSQL are
still running locally in the two terminals. No request leaves your computer, and nothing is deployed to a cloud
account.

This test checks that AgentWorks can accept, queue, execute, and record a run for this exact release. It does not
measure the quality of a real AI model because the walkthrough is still using the predictable local test model.

### How to run the test

1. In the Web Console's top navigation, select **Deployments**.
2. In the deployments table, find the row named `hello` in the `development` environment.
3. Select the `hello` link to open that deployment.
4. Under **Launch a run**, replace the **Input (JSON)** field with:

```json
{"prompt": "Explain what AgentWorks does."}
```

5. Select **Launch**.

The Console opens a page for this individual run. Watch it update while the worker executes the agent, then confirm:

- the status changes from `QUEUED` to `SUCCEEDED`;
- the answer appears in the run timeline;
- the page identifies the exact agent version, release, deployment, and model used;
- the usage section records the model call.

### What happened behind the screen

1. AgentWorks saved the run in PostgreSQL before accepting it.
2. A worker claimed the queued run.
3. The worker loaded the exact release selected by the `hello` deployment.
4. It called the local test model and recorded the progress and result.

This is a real AgentWorks run through the same queue, worker, database, release pinning, and history used with a real
model provider. Only the model is a local substitute.

## 10. Deploy the tested release to production

You have now tested release `hello-agent-1.0.0`; do not rebuild it. Promote that same unchanged release. For this local
exercise, `production` is another logical environment in the same installation:

1. Return to **Deployments**.
2. Create an environment named `production`.
3. Promote release `hello-agent-1.0.0` into a deployment named `hello` in `production`.
4. Open the new deployment and launch another run.

The development and production deployments now point to the same release. That is the value of an immutable release:
production receives exactly what you tested, not a newly assembled copy. In a real installation, environment policies
and approvals can decide whether promotion is allowed.

## 11. Stop and restart AgentWorks

Return to the terminal running AgentWorks and press **Ctrl+C**. PostgreSQL remains running.

Run the same `java -jar` command from step 3 again. Sign in to the Console and confirm that the agent, version,
release, two environments, deployments, and run history are still present. They survived because PostgreSQL—not the
Java process—owns the durable state.

### See queued work recover

To make recovery easy to observe without relying on timing:

1. Stop AgentWorks.
2. Start it with the same command, but change `--agentworks.runs.worker.enabled=true` to `false`.
3. Launch a run. It stays `QUEUED` because no worker is allowed to claim it.
4. Stop AgentWorks again.
5. Restart it with the worker enabled.
6. Reopen the queued run. It moves to `SUCCEEDED` without being resubmitted.

This proves that accepted work is stored durably: stopping the AgentWorks process does not lose the queued run, and
you do not have to submit it again. The project's automated recovery tests cover harder crash timings separately.

## 12. Clean up

The cleanup removes the disposable local database.

Stop AgentWorks with **Ctrl+C**, then remove the disposable database:

```bash
docker rm -f agentworks-postgres
```

Removing the PostgreSQL container deletes this walkthrough's agents, releases, deployments, runs, and history.

## Create more agents

One AgentWorks installation can hold many agents. Repeat steps 5–9 with a different agent name for each one. They can
share the same environments and worker pool while keeping separate definitions, releases, deployments, runs, and
history.

An agent can also call another agent through the supported agent-to-agent integrations. This is different from a
central fleet hub that manages several independent AgentWorks installations; that is not part of this walkthrough.

## Use a real model next

The test model always returns predictable local output. To build a useful agent, connect an OpenAI-compatible model
provider and change the definition's gateway and model names to match. AgentWorks also supports tools, MCP servers,
and other agents.

Continue with:

- [Models, tools, and other agents](integrations.md) to connect real capabilities;
- [Production deployment](operations/packaging.md) before exposing AgentWorks outside your computer;
- [Concepts](concepts.md) when you want the detailed lifecycle and terminology.
