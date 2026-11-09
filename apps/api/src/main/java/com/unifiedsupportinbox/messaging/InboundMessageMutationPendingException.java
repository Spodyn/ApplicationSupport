package com.unifiedsupportinbox.messaging;

/** A provider mutation raced persistence of its original Message; retry without creating a Case. */
public final class InboundMessageMutationPendingException extends RuntimeException {
    public InboundMessageMutationPendingException() {
        super("The original inbound Message is not available yet.");
    }
}
