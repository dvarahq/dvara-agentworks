package com.dvarahq.agentworks.gateway.openaicompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

/**
 * Reads Server-Sent Events lines through {@link ChatCompletionsProtocol#strictUtf8}: malformed input fails with a
 * {@link java.nio.charset.CharacterCodingException} instead of being replaced, and a leading byte-order mark is
 * skipped.
 *
 * <p>Lines end at LF, CR, or CRLF, the way {@link java.io.BufferedReader#readLine()} ends them. After a CR the reader
 * returns immediately and skips a following LF only when it later arrives, so it never blocks waiting for the next
 * byte. It reads through its own buffer, so there is one reader call per buffer rather than one per character. A line
 * longer than the caller's bound fails before it is fully retained.
 */
final class SseLineReader {

    private final Reader in;
    private final char[] buffer = new char[8192];
    private int position;
    private int limit;
    private boolean skipLineFeed;

    SseLineReader(InputStream stream) {
        this.in = ChatCompletionsProtocol.strictUtf8(stream);
    }

    /**
     * Reads the next line into {@code line}, without its terminator.
     *
     * @return false at end of stream with nothing read; a final unterminated line is returned
     */
    boolean readLine(StringBuilder line, int maxChars) throws IOException {
        line.setLength(0);
        boolean read = false;
        while (true) {
            if (position >= limit) {
                int count = in.read(buffer, 0, buffer.length);
                if (count < 0) {
                    return read;
                }
                position = 0;
                limit = count;
                continue;
            }
            if (skipLineFeed) {
                skipLineFeed = false;
                if (buffer[position] == '\n') {
                    position++;
                    continue;
                }
            }
            read = true;
            int start = position;
            while (position < limit) {
                char next = buffer[position];
                if (next == '\n' || next == '\r') {
                    append(line, start, position, maxChars);
                    position++;
                    skipLineFeed = next == '\r';
                    return true;
                }
                position++;
            }
            append(line, start, position, maxChars);
        }
    }

    private void append(StringBuilder line, int start, int end, int maxChars) {
        if (line.length() + (end - start) > maxChars) {
            throw ChatCompletionsProtocol.invalid("stream line exceeded its bound");
        }
        line.append(buffer, start, end - start);
    }
}
