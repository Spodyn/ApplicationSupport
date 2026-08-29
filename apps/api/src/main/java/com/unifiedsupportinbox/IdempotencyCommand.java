package com.unifiedsupportinbox;

/** Business command executed inside the idempotency transaction boundary. */
@FunctionalInterface
public interface IdempotencyCommand {
    IdempotencyResponse execute();
}
