-- USI-148 / E14-T02: date-specific business-hours overrides.
CREATE TABLE schedule_exceptions (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    business_hours_id uuid NOT NULL REFERENCES business_hours(id) ON DELETE CASCADE,
    exception_date date NOT NULL,
    type varchar(16) NOT NULL,
    start_time time,
    end_time time,
    note varchar(512),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_schedule_exceptions_type
        CHECK (type IN ('CLOSED', 'OPEN', 'OVERRIDE')),
    CONSTRAINT chk_schedule_exceptions_interval
        CHECK ((start_time IS NULL AND end_time IS NULL) OR (start_time < end_time)),
    CONSTRAINT chk_schedule_exceptions_closed_full_day
        CHECK (type <> 'CLOSED' OR (start_time IS NULL AND end_time IS NULL)),
    CONSTRAINT chk_schedule_exceptions_note
        CHECK (note IS NULL OR char_length(note) BETWEEN 1 AND 512),
    CONSTRAINT uq_schedule_exceptions_interval
        UNIQUE (business_hours_id, exception_date, type, start_time, end_time)
);

CREATE INDEX idx_schedule_exceptions_schedule_date
    ON schedule_exceptions (business_hours_id, exception_date, start_time, end_time, id);
