package com.dvarahq.agentworks.packaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootstrapTokenPrinterTest {
    @Test
    void readsOnlyABoundedRegularTokenFile(@TempDir Path directory) throws IOException {
        Path token = directory.resolve("bootstrap-token");
        Files.writeString(token, "one-time-value\n");
        var bytes = new ByteArrayOutputStream();

        BootstrapTokenPrinter.print(token, new PrintStream(bytes));

        assertThat(bytes.toString()).isEqualTo("one-time-value\n");
        assertThatThrownBy(() -> BootstrapTokenPrinter.print(directory.resolve("missing"), System.out))
                .isInstanceOf(IOException.class).hasMessageContaining("not present");
    }
}
