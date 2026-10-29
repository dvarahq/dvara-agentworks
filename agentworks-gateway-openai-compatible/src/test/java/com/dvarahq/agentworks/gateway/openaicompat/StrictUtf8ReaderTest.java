package com.dvarahq.agentworks.gateway.openaicompat;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** The strict UTF-8 reader skips one leading byte-order mark whichever read or skip method consumes it (#174). */
class StrictUtf8ReaderTest {

    private static Reader reader(String text) {
        return ChatCompletionsProtocol.strictUtf8(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    // #174 low 3: skip() went straight to the underlying reader, so a first skip consumed the byte-order mark as if it
    // were content, and the next read returned the character that should have been skipped.
    @Test
    void aFirstSkipNeverCountsTheByteOrderMark() throws IOException {
        try (Reader reader = reader("﻿abc")) {
            assertThat(reader.skip(1)).isEqualTo(1);
            assertThat((char) reader.read()).isEqualTo('b');
        }
        try (Reader reader = reader("﻿abc")) {
            assertThat(reader.skip(10)).isEqualTo(3);
            assertThat(reader.read()).isEqualTo(-1);
        }
    }

    @Test
    void singleCharacterReadsSkipTheByteOrderMarkOnlyAtTheStart() throws IOException {
        try (Reader reader = reader("﻿a﻿b")) {
            StringBuilder read = new StringBuilder();
            for (int next = reader.read(); next >= 0; next = reader.read()) read.append((char) next);
            assertThat(read.toString()).isEqualTo("a﻿b");
        }
    }
}
