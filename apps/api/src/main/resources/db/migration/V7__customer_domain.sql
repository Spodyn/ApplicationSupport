CREATE TABLE customers (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    name VARCHAR(160) NOT NULL,
    external_ref VARCHAR(160),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_customers_name_trimmed_nonblank
        CHECK (name = btrim(name) AND length(name) > 0),
    CONSTRAINT chk_customers_external_ref_trimmed_nonblank
        CHECK (external_ref IS NULL OR (external_ref = btrim(external_ref) AND length(external_ref) > 0))
);

CREATE UNIQUE INDEX uq_customers_normalized_name
    ON customers ((lower(name)));

CREATE UNIQUE INDEX uq_customers_normalized_external_ref
    ON customers ((lower(external_ref)))
    WHERE external_ref IS NOT NULL;

CREATE INDEX idx_customers_active_name
    ON customers (active, lower(name), id);
