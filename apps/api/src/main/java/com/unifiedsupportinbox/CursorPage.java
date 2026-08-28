package com.unifiedsupportinbox;

import java.util.List;
import java.util.Objects;

/** A cursor-paginated API page. The cursor is intentionally opaque to clients. */
public record CursorPage<T>(List<T> items, String nextCursor) {

    public CursorPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (nextCursor != null && nextCursor.isBlank()) {
            throw new IllegalArgumentException("nextCursor must be null or non-blank");
        }
    }
}
