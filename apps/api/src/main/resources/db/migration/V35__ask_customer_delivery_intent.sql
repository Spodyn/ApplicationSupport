-- USI-107 / E09-T07
-- Persist Ask Customer delivery intent on the logical outbound support Message so
-- provider retries/restarts can still finalize the Case only after delivery success.

ALTER TABLE messages
    ADD COLUMN ask_waiting_seconds BIGINT;

ALTER TABLE messages
    ADD CONSTRAINT ck_messages_ask_waiting_seconds CHECK (
        ask_waiting_seconds IS NULL
        OR (
            kind = 'SUPPORT'
            AND inbound = FALSE
            AND ask_waiting_seconds BETWEEN 3600 AND 2592000
        )
    );

CREATE INDEX idx_messages_pending_ask_delivery
    ON messages (case_id, delivery_status)
    WHERE ask_waiting_seconds IS NOT NULL
      AND delivery_status IN ('QUEUED', 'SENDING');
