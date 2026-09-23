-- USI-57 / E03-T02
-- NON-EXECUTED reference migration. Flyway scans db/migration, not db/conventions.

CREATE SEQUENCE case_reference_example_seq AS bigint START WITH 1 INCREMENT BY 1;

CREATE TABLE case_record_example (
    id uuid NOT NULL DEFAULT uuidv7(),
    reference varchar(13) NOT NULL DEFAULT (
        'CASE-' || lpad(nextval('case_reference_example_seq')::text, 8, '0')
    ),
    customer_id uuid NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'NEW',
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_case_record_example PRIMARY KEY (id),
    CONSTRAINT fk_case_record_example_customer
        FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE RESTRICT,
    CONSTRAINT uq_case_record_example_reference UNIQUE (reference),
    CONSTRAINT ck_case_record_example_status CHECK (
        status IN (
            'NEW',
            'VERIFICATION',
            'WAITING_FOR_CUSTOMER',
            'PARTIALLY_IGNORED',
            'IGNORED',
            'RESOLVED'
        )
    ),
    CONSTRAINT ck_case_record_example_version CHECK (version >= 0)
);

CREATE INDEX idx_case_record_example_status_updated
    ON case_record_example (status, updated_at DESC, id);
