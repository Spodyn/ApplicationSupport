-- USI-245. Reserved after V39/V40 for the pending V35 migrations in PRs #126/#133. Do not merge before they are safely sequenced.
ALTER TABLE messages ADD COLUMN reply_broadcast boolean NOT NULL DEFAULT FALSE;
ALTER TABLE messages ADD CONSTRAINT ck_messages_reply_broadcast CHECK (
    NOT reply_broadcast OR (kind = 'SUPPORT' AND inbound = FALSE AND external_thread_key IS NOT NULL)
);
