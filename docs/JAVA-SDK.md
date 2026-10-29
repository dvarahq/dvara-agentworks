# Use AgentWorks from Java

The Java SDK lets an application call AgentWorks without building HTTP requests by hand.

Import the AgentWorks BOM, then add `agentworks-sdk`. Spring Boot applications can use
`agentworks-spring-boot-starter` instead.

## Create a client

```java
try (AgentWorksClient client = AgentWorksClient.builder(URI.create("https://agentworks.example"))
        .accessTokenProvider(() -> tokenStore.resolve("agentworks/api-token"))
        .build()) {
    ApiDocument run = client.execute(ApiCall.operation(ApiOperation.GET_RUN)
            .path("id", runId)
            .build());
}
```

The token provider returns a fresh `char[]` for each request. The SDK clears it after use.

## Use it safely

- Add an idempotency key when creating work or changing lifecycle state.
- Use `ifMatch` when an operation requires the latest ETag.
- Subscribe to run events with `client.streamRun(...)`; cancel the subscription to close the connection.
- Do not skip event-sequence gaps. Reload history when the server asks for a resync.

For Spring configuration and extension points, continue with the [Spring Boot guide](spring-boot-integration.md).
