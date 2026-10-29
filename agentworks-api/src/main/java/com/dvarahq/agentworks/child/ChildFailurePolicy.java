package com.dvarahq.agentworks.child;

/** What the parent does when its child does not succeed. */
public enum ChildFailurePolicy {
    /** Resume the parent with a typed child outcome. */
    RETURN_OUTCOME,
    /** Fail the parent with the child's typed terminal outcome. */
    FAIL_PARENT
}
