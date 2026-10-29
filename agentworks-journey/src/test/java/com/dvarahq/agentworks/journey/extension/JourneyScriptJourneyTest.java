package com.dvarahq.agentworks.journey.extension;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class JourneyScriptJourneyTest {

    @Test
    void readsCommandsFromRawAndCompiledRunInput() {
        assertThat(JourneyScript.scriptedInput("tool journey_ledger entry-1"))
                .isEqualTo("tool journey_ledger entry-1");
        assertThat(JourneyScript.scriptedInput("[USER_INPUT:run-input]\ntool journey_ledger entry-1"))
                .isEqualTo("tool journey_ledger entry-1");
    }
}
