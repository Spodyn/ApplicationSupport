ALTER TABLE sla_policies ADD COLUMN customer_waiting_minutes integer NOT NULL DEFAULT 1440
    CHECK (customer_waiting_minutes BETWEEN 1 AND 43200);
ALTER TABLE case_sla ADD COLUMN operational_first_response_minutes integer;

CREATE TABLE case_operational_deadlines (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    case_id uuid NOT NULL REFERENCES cases(id) ON DELETE CASCADE,
    message_id uuid UNIQUE,
    kind text NOT NULL CHECK (kind IN ('FRT', 'NRT', 'FOLLOW_UP')),
    started_at timestamptz NOT NULL,
    warning_at timestamptz,
    due_at timestamptz NOT NULL,
    schedule jsonb,
    ended_at timestamptz,
    breached_at timestamptz,
    reminded_at timestamptz,
    CHECK (started_at <= due_at),
    CHECK (warning_at IS NULL OR warning_at BETWEEN started_at AND due_at),
    CHECK (kind <> 'FOLLOW_UP' OR (schedule IS NULL AND breached_at IS NULL))
);
CREATE UNIQUE INDEX uq_case_operational_deadline_active ON case_operational_deadlines (case_id) WHERE ended_at IS NULL;
CREATE INDEX ix_case_operational_deadline_due ON case_operational_deadlines (due_at, case_id)
    WHERE ended_at IS NULL AND reminded_at IS NULL;
CREATE INDEX ix_case_operational_deadline_history ON case_operational_deadlines (case_id, breached_at);

CREATE TABLE case_deadline_reminder_recipients (
    deadline_id uuid NOT NULL REFERENCES case_operational_deadlines(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    read_at timestamptz,
    PRIMARY KEY (deadline_id, user_id)
);
CREATE INDEX ix_case_deadline_reminder_personal ON case_deadline_reminder_recipients (user_id, deadline_id);

-- Keep the known current deadlines on upgrade. Previous response-cycle metrics stay in their original tables.
INSERT INTO case_operational_deadlines (case_id, kind, started_at, warning_at, due_at, schedule)
SELECT c.id, p.kind, LEAST(COALESCE(n.started_at, s.first_response_started_at, c.created_at),
                          COALESCE(p.warning_at, p.due_at)),
       CASE WHEN p.warning_at IS NULL THEN NULL ELSE LEAST(p.warning_at, p.due_at) END,
       p.due_at, s.response_schedule
FROM cases c JOIN case_response_sla_projection p ON p.case_id = c.id
LEFT JOIN case_sla s ON s.case_id = c.id
LEFT JOIN case_next_response_cycles n ON n.case_id = c.id AND n.ended_at IS NULL
WHERE p.due_at IS NOT NULL;

INSERT INTO case_operational_deadlines (case_id, kind, started_at, due_at)
SELECT id, 'FOLLOW_UP', LEAST(updated_at, waiting_until), waiting_until
FROM cases WHERE status = 'WAITING_FOR_CUSTOMER';

CREATE VIEW case_operational_deadline_projection AS
SELECT d.*,
       CASE WHEN d.kind = 'FOLLOW_UP'
            THEN CASE WHEN d.due_at <= statement_timestamp() THEN 'FOLLOW_UP_DUE' ELSE 'ON_TRACK' END
            WHEN d.due_at <= statement_timestamp() THEN 'BREACHED'
            WHEN d.warning_at <= statement_timestamp() THEN 'WARNING'
            ELSE 'ON_TRACK' END AS state
FROM case_operational_deadlines d JOIN cases c ON c.id = d.case_id
WHERE d.ended_at IS NULL
  AND ((d.kind = 'FOLLOW_UP' AND c.status = 'WAITING_FOR_CUSTOMER')
      OR (d.kind <> 'FOLLOW_UP' AND c.status IN ('NEW', 'PARTIALLY_IGNORED', 'VERIFICATION')));
