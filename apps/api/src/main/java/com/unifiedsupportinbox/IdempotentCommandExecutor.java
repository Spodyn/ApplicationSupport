package com.unifiedsupportinbox;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class IdempotentCommandExecutor {

    private static final int MAX_COMMAND_SCOPE_LENGTH = 128;

    private final TransactionTemplate transactions;
    private final IdempotencyStore store;
    private final CanonicalRequestHasher hasher;

    IdempotentCommandExecutor(
            PlatformTransactionManager transactionManager,
            IdempotencyStore store,
            CanonicalRequestHasher hasher) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.store = store;
        this.hasher = hasher;
    }

    public IdempotencyResult execute(
            UUID userId,
            String commandScope,
            String idempotencyKey,
            Object canonicalRequest,
            IdempotencyCommand command) {
        Objects.requireNonNull(userId, "userId is required");
        Objects.requireNonNull(command, "command is required");
        String scope = validatedScope(commandScope);
        String key = validatedKey(idempotencyKey);
        String requestHash = hasher.hash(canonicalRequest);

        IdempotencyResult result = transactions.execute(ignored -> executeInTransaction(
                userId,
                scope,
                key,
                requestHash,
                command));
        return Objects.requireNonNull(result, "idempotency transaction returned no result");
    }

    private IdempotencyResult executeInTransaction(
            UUID userId,
            String commandScope,
            String key,
            String requestHash,
            IdempotencyCommand command) {
        while (true) {
            Optional<UUID> created = store.tryCreate(userId, commandScope, key, requestHash);
            if (created.isPresent()) {
                IdempotencyResponse response = Objects.requireNonNull(
                        command.execute(),
                        "idempotent command returned no response");
                store.complete(created.orElseThrow(), response);
                return new IdempotencyResult(response.status(), response.body(), false);
            }

            if (store.deleteIfExpired(userId, commandScope, key)) {
                continue;
            }

            Optional<IdempotencyStore.StoredIdempotency> current = store.find(userId, commandScope, key);
            if (current.isEmpty()) {
                continue;
            }

            IdempotencyStore.StoredIdempotency stored = current.orElseThrow();
            if (!stored.requestHash().equals(requestHash)) {
                throw ApiProblemException.conflict(
                        "This Idempotency-Key was already used with a different request.");
            }
            if (stored.responseStatus() == null || stored.responseBody() == null) {
                throw new IllegalStateException("committed idempotency record has no replay response");
            }
            return new IdempotencyResult(stored.responseStatus(), stored.responseBody(), true);
        }
    }

    private static String validatedScope(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_COMMAND_SCOPE_LENGTH) {
            throw new IllegalArgumentException("command scope must contain 1 to 128 characters");
        }
        return value;
    }

    private static String validatedKey(String value) {
        if (value == null || value.isBlank() || value.length() > ApiV1Conventions.MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw ApiProblemException.validationFailed("Idempotency-Key must contain 1 to 128 characters.");
        }
        if (value.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw ApiProblemException.validationFailed("Idempotency-Key must not contain control characters.");
        }
        return value;
    }
}
