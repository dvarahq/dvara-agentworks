package com.dvarahq.agentworks.runplane.admission.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** W3C Trace Context parsing of the admission traceparent header (#32, #175). */
class TraceparentTest {
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Test
    void aVersion00HeaderHasExactlyFourFields() {
        assertThat(RunAdmissionController.traceId("00-" + TRACE + "-00f067aa0ba902b7-01")).isEqualTo(TRACE);
        assertThat(RunAdmissionController.traceId("00-" + TRACE + "-00f067aa0ba902b7-01-extra")).isNull();
    }

    // #175 low: a future version may append fields; a parser reads its first four and ignores the rest.
    @Test
    void aFutureVersionHeaderWithExtraFieldsIsReadFromItsFirstFourFields() {
        assertThat(RunAdmissionController.traceId("cc-" + TRACE + "-00f067aa0ba902b7-01-what-the-future-holds"))
                .isEqualTo(TRACE);
        assertThat(RunAdmissionController.traceId("cc-" + TRACE + "-00f067aa0ba902b7-01")).isEqualTo(TRACE);
        assertThat(RunAdmissionController.traceId("ff-" + TRACE + "-00f067aa0ba902b7-01-x")).isNull();
        assertThat(RunAdmissionController.traceId("cc-" + TRACE + "-00f067aa0ba902b7-01x")).isNull();
    }
}
