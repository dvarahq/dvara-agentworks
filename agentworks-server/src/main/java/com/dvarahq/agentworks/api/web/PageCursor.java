package com.dvarahq.agentworks.api.web;

import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Opaque keyset cursors and page limits for {@code /api/v1} collections (issue #35). A cursor encodes the sort keys
 * of the last item a page returned; it carries no authority, because every read stays bound to the caller's
 * workspace, so a forged cursor can only move the position within what the caller may already list. A cursor is
 * versioned ({@code v1.}), bounded, and strictly parsed; anything else is refused with 400 {@code INVALID_CURSOR}.
 */
public final class PageCursor {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    private static final int MAX_CURSOR_LENGTH = 1024;
    private static final String VERSION = "v1.";

    private PageCursor() {}

    /**
     * Encodes the sort keys of a position. Each key is lower-case hexadecimal, so any text is a valid key and no cursor
     * can resemble a credential, which the server refuses anywhere in a URL.
     */
    public static String encode(String... keys) {
        StringBuilder cursor = new StringBuilder(VERSION);
        for (int index = 0; index < keys.length; index++) {
            if (index > 0) cursor.append('.');
            cursor.append(HexFormat.of().formatHex(keys[index].getBytes(StandardCharsets.UTF_8)));
        }
        return cursor.toString();
    }

    /** The {@code count} sort keys of {@code cursor}, or {@code null} for the first page. */
    public static List<String> decode(String cursor, int count) {
        if (cursor == null) return null;
        if (cursor.isEmpty() || cursor.length() > MAX_CURSOR_LENGTH || !cursor.startsWith(VERSION)) throw invalid();
        String[] parts = cursor.substring(VERSION.length()).split("\\.", -1);
        if (parts.length != count) throw invalid();
        List<String> keys = new ArrayList<>(count);
        for (String part : parts) {
            try {
                if (!part.matches("(?:[0-9a-f]{2})*")) throw invalid();
                byte[] bytes = HexFormat.of().parseHex(part);
                keys.add(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString());
            } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException malformed) {
                throw invalid();
            }
        }
        return keys;
    }

    public static String instant(Instant instant) {
        return Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, instant));
    }

    /** Positions outside the range every supported database stores are never produced, so they are refused. */
    private static final Instant EARLIEST = Instant.parse("1970-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59Z");

    public static Instant instant(String key) {
        Instant value;
        try {
            value = Instant.EPOCH.plus(Long.parseLong(key), ChronoUnit.MICROS);
        } catch (RuntimeException malformed) {
            throw invalid();
        }
        if (value.isBefore(EARLIEST) || value.isAfter(LATEST) || !instant(value).equals(key)) throw invalid();
        return value;
    }

    public static UUID uuid(String key) {
        try {
            UUID value = UUID.fromString(key);
            if (!value.toString().equals(key)) throw invalid();
            return value;
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
    }

    public static long sequence(String key) {
        try {
            long value = Long.parseLong(key);
            if (value < 0 || !Long.toString(value).equals(key)) throw invalid();
            return value;
        } catch (NumberFormatException malformed) {
            throw invalid();
        }
    }

    /** A requested page size: {@code defaultLimit} when absent, otherwise 1 to {@code max}. */
    public static int limit(Integer requested, int defaultLimit, int max) {
        if (requested == null) return defaultLimit;
        if (requested < 1 || requested > max) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT",
                    "limit must be between 1 and " + max + ".");
        }
        return requested;
    }

    public static int limit(Integer requested) {
        return limit(requested, DEFAULT_LIMIT, MAX_LIMIT);
    }

    private static ApiRequestException invalid() {
        return new ApiRequestException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR",
                "The cursor is not valid for this collection.");
    }
}
