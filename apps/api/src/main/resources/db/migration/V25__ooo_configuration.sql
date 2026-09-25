CREATE TABLE ooo_config (
    id SMALLINT PRIMARY KEY DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    message_template TEXT NOT NULL,
    send_once_per_closure BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 1,
    updated_by VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_ooo_config_singleton CHECK (id = 1),
    CONSTRAINT chk_ooo_config_template CHECK (length(btrim(message_template)) > 0 AND length(message_template) <= 4000),
    CONSTRAINT chk_ooo_config_version CHECK (version >= 1),
    CONSTRAINT chk_ooo_config_updated_by CHECK (updated_by = btrim(updated_by) AND length(updated_by) > 0)
);

INSERT INTO ooo_config (id, enabled, message_template, send_once_per_closure, updated_by)
VALUES (1, FALSE, 'Dziękujemy za wiadomość. Wrócimy do Ciebie {{next_opening_date}} o {{next_opening_time}} ({{timezone}}).', TRUE, 'system:bootstrap');
