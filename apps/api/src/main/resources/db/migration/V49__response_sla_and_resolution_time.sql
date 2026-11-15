-- Existing case deadlines and completion times remain authoritative for historical FRT.
ALTER TABLE sla_policies
    ADD COLUMN next_response_minutes integer NOT NULL DEFAULT 30 CHECK (next_response_minutes BETWEEN 1 AND 10080);

-- Upgrade the untouched seeded policy. Preserve a site's customized FRT.
UPDATE sla_policies SET first_response_target = INTERVAL '15 minutes'
WHERE version = 1 AND first_response_target = INTERVAL '60 minutes';

ALTER TABLE case_sla
    ADD COLUMN response_schedule jsonb,
    ADD COLUMN next_response_minutes integer,
    ADD COLUMN response_policy_version bigint,
    ADD COLUMN response_pause_waiting boolean,
    ADD COLUMN last_response_sender text CHECK (last_response_sender IN ('CUSTOMER', 'SUPPORT', 'SYSTEM')),
    ADD COLUMN first_response_warning_at timestamptz,
    ADD COLUMN first_response_stopped_at timestamptz,
    ADD COLUMN first_response_breached_at timestamptz,
    ADD COLUMN resolved_pause_seconds bigint NOT NULL DEFAULT 0 CHECK (resolved_pause_seconds >= 0);

CREATE TABLE case_next_response_cycles (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    case_id uuid NOT NULL REFERENCES cases(id) ON DELETE CASCADE,
    opening_message_id uuid NOT NULL UNIQUE,
    started_at timestamptz NOT NULL,
    warning_at timestamptz NOT NULL,
    due_at timestamptz NOT NULL,
    ended_at timestamptz,
    outcome text CHECK (outcome IN ('RESPONDED', 'RESOLVED', 'IGNORED')),
    response_message_id uuid,
    CHECK (started_at <= warning_at AND warning_at <= due_at),
    CHECK ((ended_at IS NULL) = (outcome IS NULL))
);

CREATE UNIQUE INDEX uq_case_active_next_response ON case_next_response_cycles (case_id) WHERE ended_at IS NULL;
CREATE INDEX idx_case_next_response_history ON case_next_response_cycles (case_id, started_at DESC, id DESC);

CREATE TABLE case_resolution_times (
    case_id uuid NOT NULL REFERENCES cases(id) ON DELETE CASCADE,
    ended_at timestamptz NOT NULL,
    wall_seconds bigint NOT NULL CHECK (wall_seconds >= 0),
    working_seconds bigint CHECK (working_seconds >= 0),
    PRIMARY KEY (case_id, ended_at)
);

-- Old calendars were mutable. Preserve known wall time; leave unprovable working time NULL.
INSERT INTO case_resolution_times (case_id, ended_at, wall_seconds)
SELECT id, resolved_at, GREATEST(0, trunc(EXTRACT(epoch FROM resolved_at - created_at)))::bigint
FROM cases WHERE resolved_at IS NOT NULL;

UPDATE case_sla s
SET first_response_stopped_at = COALESCE(c.resolved_at, c.ignored_at)
FROM cases c
WHERE s.case_id = c.id AND s.first_response_completed_at IS NULL
  AND COALESCE(c.resolved_at, c.ignored_at) IS NOT NULL;

UPDATE case_sla
SET first_response_breached_at = first_response_due_at
WHERE COALESCE(first_response_completed_at, first_response_stopped_at) > first_response_due_at;

WITH observed AS (
    SELECT m.case_id, m.id, 'CUSTOMER' AS sender, m.created_at AS observed_at
    FROM messages m WHERE m.inbound AND m.kind = 'CUSTOMER'
    UNION ALL
    SELECT m.case_id, m.id, m.kind, a.finished_at
    FROM messages m JOIN delivery_attempts a ON a.message_id = m.id
    WHERE NOT m.inbound AND m.kind IN ('SUPPORT', 'SYSTEM')
      AND m.delivery_status IN ('SENT', 'DELIVERED')
      AND a.finished_at IS NOT NULL AND a.error_category IS NULL
), last_sender AS (
    SELECT DISTINCT ON (case_id) case_id, sender
    FROM observed ORDER BY case_id, observed_at DESC, id DESC
)
UPDATE case_sla s SET last_response_sender = last_sender.sender
FROM last_sender WHERE last_sender.case_id = s.case_id;

CREATE FUNCTION response_sla_state(workflow text, warning_at timestamptz, due_at timestamptz, observed_at timestamptz)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE WHEN workflow = 'RESOLVED' THEN 'NEUTRAL'
                WHEN workflow = 'WAITING_ON_CUSTOMER' THEN 'PAUSED'
                WHEN due_at <= observed_at THEN 'BREACHED'
                WHEN warning_at <= observed_at THEN 'WARNING'
                WHEN due_at IS NOT NULL THEN 'ON_TRACK'
                ELSE 'NEUTRAL' END
$$;

CREATE VIEW case_response_sla_projection AS
SELECT c.id AS case_id,
       response_sla_state(c.workflow_status, active.warning_at, active.due_at, statement_timestamp()) AS state,
       active.kind, active.warning_at, active.due_at, s.last_response_sender,
       (s.first_response_breached_at IS NOT NULL OR EXISTS (
           SELECT 1 FROM case_next_response_cycles h
           WHERE h.case_id = c.id AND (h.ended_at > h.due_at OR (h.ended_at = h.due_at AND h.outcome <> 'RESPONDED'))
       )) AS historically_breached
FROM cases c
LEFT JOIN case_sla s ON s.case_id = c.id
LEFT JOIN case_next_response_cycles n ON n.case_id = c.id AND n.ended_at IS NULL
LEFT JOIN organization_settings settings ON settings.singleton
LEFT JOIN LATERAL (
    SELECT CASE WHEN n.id IS NOT NULL THEN 'NRT' ELSE 'FRT' END AS kind,
           COALESCE(n.warning_at, s.first_response_warning_at,
                    s.first_response_due_at - LEAST(s.first_response_due_at - s.first_response_started_at, make_interval(mins => COALESCE(settings.sla_warning_before_minutes, 15)))) AS warning_at,
           COALESCE(n.due_at, s.first_response_due_at) AS due_at
    WHERE c.workflow_status IN ('NEW', 'OPEN')
      AND (n.id IS NOT NULL OR (s.first_response_completed_at IS NULL AND s.first_response_stopped_at IS NULL
                                AND s.first_response_due_at IS NOT NULL))
) active ON TRUE;
