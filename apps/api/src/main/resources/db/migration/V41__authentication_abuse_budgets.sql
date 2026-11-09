-- Shared across application instances; no plaintext account identifiers or IPs.
CREATE TABLE authentication_abuse_budgets (
    bucket_key varchar(80) NOT NULL,
    attempts integer NOT NULL,
    expires_at timestamptz NOT NULL,
    next_allowed_at timestamptz NOT NULL,
    CONSTRAINT pk_authentication_abuse_budgets PRIMARY KEY (bucket_key),
    CONSTRAINT ck_authentication_abuse_budgets_attempts CHECK (attempts >= 0)
);
CREATE INDEX idx_authentication_abuse_budgets_expiry ON authentication_abuse_budgets (expires_at);
