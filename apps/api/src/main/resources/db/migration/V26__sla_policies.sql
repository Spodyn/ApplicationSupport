CREATE TABLE sla_policies (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    name VARCHAR(160) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    first_response_target INTERVAL NOT NULL,
    unclaimed_warning INTERVAL NOT NULL,
    unclaimed_breach INTERVAL NOT NULL,
    in_progress_warning INTERVAL NOT NULL,
    in_progress_breach INTERVAL NOT NULL,
    pause_waiting BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_sla_policies_name CHECK (name = btrim(name) AND length(name) > 0),
    CONSTRAINT chk_sla_policies_version CHECK (version >= 1)
);
CREATE UNIQUE INDEX uq_sla_policies_one_active ON sla_policies ((active)) WHERE active;
INSERT INTO sla_policies (name, active, first_response_target, unclaimed_warning, unclaimed_breach, in_progress_warning, in_progress_breach, pause_waiting)
VALUES ('Default policy', TRUE, INTERVAL '60 minutes', INTERVAL '15 minutes', INTERVAL '60 minutes', INTERVAL '30 minutes', INTERVAL '120 minutes', TRUE);
