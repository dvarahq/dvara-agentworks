package com.dvarahq.agentworks.journey.extension;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The journey's one reference capability: a local tool with an observable external effect. Each call appends its
 * {@code entry} argument as one line to an effects file outside the server's database and returns a receipt. The
 * journey reads that file to count effects, so a repeated execution (for example after a restart) is visible.
 *
 * <p>Test-only: it reaches the server only through the public {@link ToolCallback} extension point of #34, as an
 * application tool. It is not part of the product.
 */
public final class JourneyLedgerTool implements ToolCallback {
    public static final String NAME = "journey_ledger";
    public static final String INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"entry\":{\"type\":\"string\"}},\"required\":[\"entry\"]}";
    private static final Pattern ENTRY = Pattern.compile("\"entry\"\\s*:\\s*\"([A-Za-z0-9._-]{1,128})\"");

    private final Path effects;
    private final ToolDefinition definition = ToolDefinition.builder().name(NAME)
            .description("Appends one entry to the journey ledger and returns its receipt.")
            .inputSchema(INPUT_SCHEMA)
            .build();

    public JourneyLedgerTool(Path effects) {
        this.effects = Objects.requireNonNull(effects, "effects");
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        Matcher entry = ENTRY.matcher(toolInput == null ? "" : toolInput);
        if (!entry.find()) {
            throw new IllegalArgumentException("the ledger needs an entry");
        }
        String line = entry.group(1) + "\n";
        synchronized (JourneyLedgerTool.class) {
            try (FileChannel channel = FileChannel.open(effects, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                channel.write(ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
        return "ledger-receipt:" + entry.group(1);
    }
}
