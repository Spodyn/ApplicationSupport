-- USI-164 / E16-T02
-- Rebuildable daily analytics projections. Source events remain canonical;
-- these rows are disposable query accelerators and are never the source of
-- workflow, SLA, or audit decisions.

CREATE TABLE analytics_daily_case_metrics (
    id uuid NOT NULL DEFAULT uuidv7(),
    reporting_date date NOT NULL,
    timezone_id varchar(64) NOT NULL,
    customer_id uuid,
    provider varchar(16),
    user_id uuid,
    created_count bigint NOT NULL DEFAULT 0,
    claimed_count bigint NOT NULL DEFAULT 0,
    first_response_count bigint NOT NULL DEFAULT 0,
    first_response_duration_seconds bigint NOT NULL DEFAULT 0,
    resolved_count bigint NOT NULL DEFAULT 0,
    ignored_count bigint NOT NULL DEFAULT 0,
    resolution_duration_seconds bigint NOT NULL DEFAULT 0,
    rebuilt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_analytics_daily_case_metrics PRIMARY KEY (id),
    CONSTRAINT fk_analytics_daily_case_metrics_customer
        FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE,
    CONSTRAINT fk_analytics_daily_case_metrics_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_analytics_daily_case_metrics_provider
        CHECK (provider IS NULL OR provider IN ('SLACK', 'TEAMS', 'TELEGRAM')),
    CONSTRAINT ck_analytics_daily_case_metrics_counts CHECK (
        created_count >= 0 AND claimed_count >= 0 AND first_response_count >= 0
        AND resolved_count >= 0 AND ignored_count >= 0
    ),
    CONSTRAINT ck_analytics_daily_case_metrics_durations CHECK (
        first_response_duration_seconds >= 0 AND resolution_duration_seconds >= 0
    )
);

CREATE UNIQUE INDEX uq_analytics_daily_case_metrics_dimensions
    ON analytics_daily_case_metrics (reporting_date, timezone_id, customer_id, provider, user_id) NULLS NOT DISTINCT;

CREATE INDEX idx_analytics_daily_case_metrics_dashboard
    ON analytics_daily_case_metrics (reporting_date DESC, customer_id, provider, user_id);

CREATE TABLE analytics_aggregate_rebuilds (
    reporting_date date NOT NULL,
    timezone_id varchar(64) NOT NULL,
    completed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source_high_watermark timestamptz NOT NULL,
    CONSTRAINT pk_analytics_aggregate_rebuilds PRIMARY KEY (reporting_date, timezone_id)
);
