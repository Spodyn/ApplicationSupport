package com.unifiedsupportinbox.ooo;

import java.time.Instant;

/** Rendered administrator preview; nextOpening is null for a schedule configuration fault. */
public record OutOfOfficePreviewView(String message, Instant nextOpening, String timezone) {
}
