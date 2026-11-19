-- Connector can accept an asynchronous send with HTTP 202 and no ResourceResponse.
-- Keep the real HTTP receipt separately; do not invent a provider message ID.
CREATE TABLE teams_outbound_acceptance (
    message_id uuid PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,
    http_status integer NOT NULL CHECK (http_status = 202),
    accepted_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
