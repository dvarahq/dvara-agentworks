package com.dvarahq.agentworks.api.web;

import java.util.List;
import java.util.function.Function;

/**
 * The collection envelope of every {@code /api/v1} list operation (issue #35): {@code items} in the collection's
 * documented order and {@code nextCursor}, the opaque position after the last item, or {@code null} when the
 * collection has no further items. A client passes {@code nextCursor} back as the {@code cursor} query parameter
 * with the same filters to read the next page.
 */
public record Page<T>(List<T> items, String nextCursor) {
    public Page {
        items = List.copyOf(items);
    }

    /** An unpaged, bounded collection. */
    public static <T> Page<T> of(List<T> items) {
        return new Page<>(items, null);
    }

    /**
     * A page from {@code fetched}, which holds up to {@code limit + 1} items: the extra item only proves that more
     * follow, and the cursor is the position of the last returned item.
     */
    public static <T> Page<T> of(List<T> fetched, int limit, Function<T, String> cursor) {
        if (fetched.size() <= limit) return new Page<>(fetched, null);
        List<T> items = fetched.subList(0, limit);
        return new Page<>(items, cursor.apply(items.getLast()));
    }

    public <R> Page<R> map(Function<T, R> mapper) {
        return new Page<>(items.stream().map(mapper).toList(), nextCursor);
    }
}
