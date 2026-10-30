-- USI-245. Hold merge until pending V35 migration conflicts are sequenced.
ALTER TABLE messages ADD COLUMN reply_broadcast boolean NOT NULL DEFAULT FALSE;
ALTER TABLE messages ADD CONSTRAINT ck_messages_reply_broadcast CHECK (
    NOT reply_broadcast OR (kind = 'SUPPORT' AND inbound = FALSE AND external_thread_key IS NOT NULL)
);
