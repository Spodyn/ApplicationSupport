CREATE TABLE business_hours (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    timezone_id VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_by VARCHAR(320) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_business_hours_timezone
        CHECK (timezone_id = btrim(timezone_id) AND length(timezone_id) > 0),
    CONSTRAINT chk_business_hours_updated_by
        CHECK (updated_by = btrim(updated_by) AND length(updated_by) > 0)
);

CREATE UNIQUE INDEX uq_business_hours_single_active
    ON business_hours ((1))
    WHERE active;

CREATE TABLE business_hour_intervals (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    business_hours_id UUID NOT NULL REFERENCES business_hours(id) ON DELETE CASCADE,
    day_of_week SMALLINT NOT NULL,
    start_time TIME(0) WITHOUT TIME ZONE NOT NULL,
    end_time TIME(0) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT chk_business_hour_intervals_day
        CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT chk_business_hour_intervals_order
        CHECK (start_time < end_time),
    CONSTRAINT uq_business_hour_interval_exact
        UNIQUE (business_hours_id, day_of_week, start_time, end_time)
);

CREATE INDEX idx_business_hour_intervals_schedule_day
    ON business_hour_intervals (business_hours_id, day_of_week, start_time, end_time);

WITH default_schedule AS (
    INSERT INTO business_hours (timezone_id, active, updated_by)
    VALUES ('UTC', TRUE, 'system:bootstrap')
    RETURNING id
)
INSERT INTO business_hour_intervals (business_hours_id, day_of_week, start_time, end_time)
SELECT default_schedule.id, weekday.day_of_week, TIME '09:00', TIME '17:00'
FROM default_schedule
CROSS JOIN (VALUES (1), (2), (3), (4), (5)) AS weekday(day_of_week);
