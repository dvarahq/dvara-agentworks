package com.dvarahq.agentworks.architecture.fixture.core;

import com.dvarahq.agentworks.architecture.fixture.forbidden.ForbiddenBoundaryComponent;

public final class CoreDependingOnForbiddenBoundary {

    private final ForbiddenBoundaryComponent forbiddenComponent;

    public CoreDependingOnForbiddenBoundary(ForbiddenBoundaryComponent forbiddenComponent) {
        this.forbiddenComponent = forbiddenComponent;
    }
}
