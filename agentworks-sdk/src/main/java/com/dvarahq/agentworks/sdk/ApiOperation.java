package com.dvarahq.agentworks.sdk;

/** Stable v1 operation names exposed by the Java SDK. */
public enum ApiOperation {
    LIST_AGENTS("GET", "/api/v1/agents"),
    CREATE_AGENT("POST", "/api/v1/agents"),
    GET_AGENT("GET", "/api/v1/agents/{definitionId}"),
    LIST_AGENT_VERSIONS("GET", "/api/v1/agents/{definitionId}/versions"),
    PUBLISH_AGENT_VERSION("POST", "/api/v1/agents/{definitionId}/versions"),
    GET_AGENT_VERSION("GET", "/api/v1/agents/{definitionId}/versions/{version}"),
    CREATE_RELEASE("POST", "/api/v1/agent-versions/{versionId}/releases"),
    LIST_RELEASES("GET", "/api/v1/releases"),
    GET_RELEASE("GET", "/api/v1/releases/{id}"),
    GET_RELEASE_MANIFEST("GET", "/api/v1/releases/{id}/manifest"),
    GET_RELEASE_LIFECYCLE("GET", "/api/v1/releases/{id}/lifecycle"),
    TRANSITION_RELEASE("POST", "/api/v1/releases/{id}/{action}"),
    LIST_ENVIRONMENTS("GET", "/api/v1/environments"),
    CREATE_ENVIRONMENT("POST", "/api/v1/environments"),
    GET_ENVIRONMENT("GET", "/api/v1/environments/{id}"),
    GET_PROMOTION_POLICY("GET", "/api/v1/environments/{id}/promotion-policy"),
    PUT_PROMOTION_POLICY("PUT", "/api/v1/environments/{id}/promotion-policy"),
    PROMOTE("POST", "/api/v1/environments/{environmentId}/promotions"),
    LIST_DEPLOYMENTS("GET", "/api/v1/deployments"),
    GET_DEPLOYMENT("GET", "/api/v1/deployments/{id}"),
    ROLLBACK_DEPLOYMENT("POST", "/api/v1/deployments/{id}/rollback"),
    LIST_ROUTING_TARGETS("GET", "/api/v1/routing-generations/{id}/targets"),
    GET_COHORT("GET", "/api/v1/cohorts/{id}"),
    TRANSITION_COHORT("POST", "/api/v1/cohorts/{id}/{action}"),
    OBSERVE_QUIESCENCE("GET", "/api/v1/cohorts/{id}/quiescence"),
    RETIRE_COHORT("POST", "/api/v1/cohorts/{id}/retire"),
    SUBMIT_RUN("POST", "/api/v1/deployments/{deploymentId}/runs"),
    LIST_RUNS("GET", "/api/v1/runs"),
    GET_RUN("GET", "/api/v1/runs/{id}"),
    LIST_RUN_EVENTS("GET", "/api/v1/runs/{id}/events"),
    LIST_RUN_STEPS("GET", "/api/v1/runs/{id}/steps"),
    CANCEL_RUN("POST", "/api/v1/runs/{id}/cancel"),
    PAUSE_RUN("POST", "/api/v1/runs/{id}/pause"),
    RESUME_RUN("POST", "/api/v1/runs/{id}/resume"),
    PLAN_REPLAY("GET", "/api/v1/runs/{id}/replay/plan"),
    REPLAY_RUN("POST", "/api/v1/runs/{id}/replay"),
    LIST_INTERACTIONS("GET", "/api/v1/interactions"),
    GET_INTERACTION("GET", "/api/v1/interactions/{interactionId}"),
    RESPOND_TO_INTERACTION("POST", "/api/v1/interactions/{interactionId}/response"),
    LIST_OPERATIONS("GET", "/api/v1/operations"),
    GET_OPERATION("GET", "/api/v1/operations/{operationId}"),
    CANCEL_OPERATION("POST", "/api/v1/operations/{operationId}/cancel"),
    UPLOAD_ARTIFACT("POST", "/api/v1/artifacts"),
    LIST_ARTIFACTS("GET", "/api/v1/artifacts"),
    GET_ARTIFACT("GET", "/api/v1/artifacts/{artifactId}"),
    DOWNLOAD_ARTIFACT("GET", "/api/v1/artifacts/{artifactId}/content"),
    EXPIRE_ARTIFACT("POST", "/api/v1/artifacts/{artifactId}/expire"),
    LIST_ARTIFACT_PINS("GET", "/api/v1/artifacts/{artifactId}/pins"),
    PIN_ARTIFACT("POST", "/api/v1/artifacts/{artifactId}/pins"),
    UNPIN_ARTIFACT("DELETE", "/api/v1/artifacts/{artifactId}/pins/{pinId}"),
    CREATE_EVALUATION_DATASET("POST", "/api/v1/evaluation-datasets"),
    LIST_EVALUATION_DATASETS("GET", "/api/v1/evaluation-datasets"),
    CREATE_EVALUATOR("POST", "/api/v1/evaluators"),
    LIST_EVALUATORS("GET", "/api/v1/evaluators"),
    START_EVALUATION("POST", "/api/v1/evaluation-runs"),
    GET_EVALUATION("GET", "/api/v1/evaluation-runs/{id}"),
    GET_EVALUATION_REPORT("GET", "/api/v1/evaluation-runs/{id}/report"),
    CANCEL_EVALUATION("POST", "/api/v1/evaluation-runs/{id}/cancel"),
    RESUME_EVALUATION("POST", "/api/v1/evaluation-runs/{id}/resume"),
    CREATE_EVALUATION_BASELINE("POST", "/api/v1/evaluation-baselines"),
    COMPARE_EVALUATION_BASELINE("GET", "/api/v1/evaluation-baselines/{id}/comparison");

    private final String method;
    private final String pathTemplate;

    ApiOperation(String method, String pathTemplate) {
        this.method = method;
        this.pathTemplate = pathTemplate;
    }

    public String method() { return method; }
    public String pathTemplate() { return pathTemplate; }
}
