-- Owned waiting occurs only after a delivered public reply.
ALTER TABLE cases DROP CONSTRAINT ck_cases_owner_state;
ALTER TABLE cases ADD CONSTRAINT ck_cases_owner_state CHECK (
    (status = 'VERIFICATION' AND owner_user_id IS NOT NULL)
    OR (status IN ('NEW', 'PARTIALLY_IGNORED', 'IGNORED') AND owner_user_id IS NULL)
    OR status IN ('WAITING_FOR_CUSTOMER', 'RESOLVED')
) NOT VALID;
ALTER TABLE cases VALIDATE CONSTRAINT ck_cases_owner_state;

ALTER TABLE messages ADD COLUMN reply_wait_case_version bigint;
ALTER TABLE messages ADD CONSTRAINT ck_messages_reply_wait_version CHECK (
    reply_wait_case_version IS NULL OR (
        reply_wait_case_version >= 0 AND kind = 'SUPPORT' AND inbound = FALSE
        AND author_user_id IS NOT NULL AND ask_waiting_seconds IS NULL
    )
) NOT VALID;
ALTER TABLE messages VALIDATE CONSTRAINT ck_messages_reply_wait_version;
