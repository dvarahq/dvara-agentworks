package com.dvarahq.spikefixture.authoritative;

import org.springframework.ai.chat.memory.ChatMemory;

/** Deliberate test-only violation used to prove the ChatMemory authority guard is non-vacuous. */
public record AuthoritativeRunStateUsingChatMemory(ChatMemory chatMemory) {
}
