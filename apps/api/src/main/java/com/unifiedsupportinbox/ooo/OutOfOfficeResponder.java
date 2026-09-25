package com.unifiedsupportinbox.ooo;

import java.util.UUID;

/** Enqueues a durable system out-of-office response for an inbound customer message when applicable. */
public interface OutOfOfficeResponder {

    void customerMessageReceived(UUID caseId, String customerName, String correlationId);
}
