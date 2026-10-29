package com.dvarahq.agentworks.domain.identity;

/**
 * Workspace-scoped permissions that REST, Console, CLI, and channel boundaries authorize against. Roles are named
 * bundles of these permissions; optional policy providers can grant them directly without changing resource scope.
 */
public enum WorkspacePermission {
    /** Read agents, versions, releases, environments, deployments, cohorts, decisions, and runs. */
    WORKSPACE_READ,
    /** Create agent definitions and publish agent versions. */
    AGENT_WRITE,
    /** Build releases and move release aliases. */
    RELEASE_WRITE,
    /** Suspend, resume, revoke, and retire releases and record trust/dependency revocations. */
    RELEASE_ADMIN,
    /** Create environments and change their promotion policies. */
    ENVIRONMENT_ADMIN,
    /** Grant, deny, inspect, and revoke remembered tool permissions. */
    TOOL_PERMISSION_ADMIN,
    /** Promote, roll back, suspend, resume, revoke, observe, and retire deployments and cohorts. */
    DEPLOYMENT_OPERATE,
    /** Admit runs through current routing, and cancel or resume them. */
    RUN_ADMIT,
    /** Respond to a human interaction when its durable assignment also permits the principal. */
    INTERACTION_RESPOND,
    /** Upload and explicitly pin tenant-owned artifacts. */
    ARTIFACT_WRITE,
    /** Expire, validate, quarantine, unpin, and reconcile tenant-owned artifacts. */
    ARTIFACT_ADMIN,
    /** Create, list, and revoke one's own API tokens in the workspace. */
    TOKEN_SELF,
    /** List and revoke any member's API tokens in the workspace. */
    TOKEN_ADMIN,
    /** List members and grant, change, or remove membership roles. */
    MEMBER_ADMIN
}
