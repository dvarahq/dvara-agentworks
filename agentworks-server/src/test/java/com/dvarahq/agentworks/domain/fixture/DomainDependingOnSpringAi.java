package com.dvarahq.agentworks.domain.fixture;

import org.springframework.ai.fixture.FakeChatModel;

public final class DomainDependingOnSpringAi {

    private final FakeChatModel chatModel;

    public DomainDependingOnSpringAi(FakeChatModel chatModel) {
        this.chatModel = chatModel;
    }
}
