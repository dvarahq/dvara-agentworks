# Use AgentWorks with Spring Boot

AgentWorks supports two common Spring Boot setups:

1. **Call an AgentWorks server** from your application with the Java client starter.
2. **Run AgentWorks components** inside a Spring Boot application when you need deeper integration.

Most applications should start with the client starter. It keeps your application small and leaves durable execution
to the AgentWorks server.

## Connect to an AgentWorks server

Add `agentworks-spring-boot-starter`, then configure:

```properties
agentworks.client.enabled=true
agentworks.client.base-url=https://agentworks.example
agentworks.client.credential-ref=agentworks/api-token
```

Provide a `CredentialReferenceResolver` bean that can resolve the token reference. AgentWorks does not contact the
server or read the credential during startup.

Inject `AgentWorksClient` and use it like the [Java SDK](JAVA-SDK.md).

## Connect a Spring AI model

Add the Spring AI starter for your provider or supply a `ChatModel` bean. AgentWorks can then provide a
`SpringAiAgentExecutor`. If your application supplies its own `AgentExecutor`, your bean wins.

Tools are opt-in. Only callbacks you explicitly expose through a `ToolCallbackProvider` can be used by an agent.

## Credentials

Store references, not secret values, in configuration and releases. AgentWorks supports application-defined
credential providers. Common choices are:

- environment variables for local development;
- mounted files or a secret manager for production;
- a custom provider for your existing credential system.

Credentials are resolved just before use, checked against the run's workspace and scope, and cleared afterward.
Unknown, duplicated, revoked, or unavailable providers fail closed.

## PostgreSQL and transactions

Embedded durable execution needs a Spring `DataSource` and a local JDBC transaction manager bound to that same data
source. `JdbcTransactionManager` and `DataSourceTransactionManager` are supported. JPA and JTA transaction managers
are not used because AgentWorks performs local JDBC transactions.

For a production installation, use separate database identities for migrations, the Control Plane, and run workers.
See [PostgreSQL production](operations/postgresql-production.md).

## Keep the boundary simple

- Let PostgreSQL own durable state; do not keep run state only in Spring beans or caches.
- Treat model and tool output as untrusted input.
- Do not give a tool or callback more credentials than it needs.
- Use the provider conformance tests before deploying a custom model adapter.

All settings are listed in [Configuration](reference/configuration.md).
