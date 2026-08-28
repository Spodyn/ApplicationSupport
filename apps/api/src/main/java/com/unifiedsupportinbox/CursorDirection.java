package com.unifiedsupportinbox;

import java.util.Comparator;

/** Stable public sort direction names for cursor-paginated endpoints. */
public enum CursorDirection {
    ASC,
    DESC;

    public Comparator<CursorPosition> comparator() {
        Comparator<CursorPosition> natural = CursorPosition::compareNatural;
        return this == ASC ? natural : natural.reversed();
    }

    public boolean isAfter(CursorPosition candidate, CursorPosition cursor) {
        return comparator().compare(candidate, cursor) > 0;
    }
}
