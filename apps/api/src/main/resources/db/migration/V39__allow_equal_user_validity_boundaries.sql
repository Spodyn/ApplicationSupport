-- USI-72: the frozen account contract permits valid_from <= valid_until.
-- V3 used a strict (<) constraint, causing accepted requests at the same instant
-- to fail at persistence. Replace the constraint without changing existing data.
-- Sequence after all pending lower-numbered Flyway migrations are integrated.
ALTER TABLE users DROP CONSTRAINT ck_users_validity_window;
ALTER TABLE users ADD CONSTRAINT ck_users_validity_window CHECK (
    valid_from IS NULL
    OR valid_until IS NULL
    OR valid_from <= valid_until
);
