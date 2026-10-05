-- Run only through `pnpm local:slack:seed`, which verifies the local Compose container.
-- Sandbox workspace/channel IDs are supplied from the ignored .env by scripts/dev.mjs.
-- This file contains only a secret locator, never provider credentials.
BEGIN;

CREATE TEMP TABLE usi_local_slack_seed_config (
    team_id TEXT NOT NULL,
    channel_id TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO usi_local_slack_seed_config (team_id, channel_id)
VALUES (:'slack_team_id', :'slack_channel_id');

CREATE TEMP TABLE usi_local_slack_seed_result (
    sort_order INTEGER NOT NULL,
    entity TEXT NOT NULL,
    id UUID NOT NULL,
    action TEXT NOT NULL
) ON COMMIT DROP;

DO $seed$
DECLARE
    seeded_customer_id UUID;
    seeded_integration_id UUID;
    seeded_channel_id UUID;
    existing_external_ref TEXT;
    slack_team_id TEXT;
    slack_channel_id TEXT;
    customer_action TEXT := 'reused';
    integration_action TEXT := 'reused';
    channel_action TEXT := 'reused';
BEGIN
    SELECT team_id, channel_id INTO slack_team_id, slack_channel_id
    FROM usi_local_slack_seed_config;

    IF slack_team_id !~ '^T[A-Z0-9]+    IF to_regclass('public.customers') IS NULL
            OR to_regclass('public.integrations') IS NULL
            OR to_regclass('public.channels') IS NULL THEN
        RAISE EXCEPTION 'Local application schema is missing; start pnpm local:api to run Flyway first';
    END IF;

    SELECT id INTO seeded_customer_id
    FROM customers
    WHERE lower(external_ref) = 'slack-test'
    FOR UPDATE;
    IF seeded_customer_id IS NULL THEN
        SELECT id, external_ref INTO seeded_customer_id, existing_external_ref
        FROM customers
        WHERE lower(name) = lower('Slack Test Customer')
        FOR UPDATE;
        IF seeded_customer_id IS NOT NULL
                AND existing_external_ref IS NOT NULL
                AND lower(existing_external_ref) <> 'slack-test' THEN
            RAISE EXCEPTION 'Slack Test Customer already belongs to another external reference';
        END IF;
    END IF;
    IF seeded_customer_id IS NULL THEN
        INSERT INTO customers (name, external_ref)
        VALUES ('Slack Test Customer', 'slack-test')
        RETURNING id INTO seeded_customer_id;
        customer_action := 'created';
    ELSE
        IF EXISTS (
            SELECT 1 FROM customers
            WHERE lower(name) = lower('Slack Test Customer') AND id <> seeded_customer_id
        ) THEN
            RAISE EXCEPTION 'Slack Test Customer name belongs to another customer';
        END IF;
        UPDATE customers
        SET name = 'Slack Test Customer', external_ref = 'slack-test', active = TRUE,
            updated_at = now()
        WHERE id = seeded_customer_id
          AND (name, external_ref, active) IS DISTINCT FROM
              ('Slack Test Customer', 'slack-test', TRUE);
    END IF;

    SELECT id INTO seeded_integration_id
    FROM integrations
    WHERE provider = 'SLACK' AND workspace_external_id = slack_team_id
      AND status <> 'DISABLED'
    FOR UPDATE;
    IF seeded_integration_id IS NULL THEN
        SELECT id INTO seeded_integration_id
        FROM integrations
        WHERE provider = 'SLACK' AND workspace_external_id = slack_team_id
        ORDER BY created_at, id
        LIMIT 1
        FOR UPDATE;
    END IF;
    IF seeded_integration_id IS NULL THEN
        INSERT INTO integrations (
            provider, display_name, status, health,
            workspace_external_id, workspace_name, secret_ref
        ) VALUES (
            'SLACK', 'TestApp', 'ENABLED', 'UNKNOWN',
            slack_team_id, 'TestApp', 'slack/development-workspace'
        ) RETURNING id INTO seeded_integration_id;
        integration_action := 'created';
    ELSE
        UPDATE integrations
        SET display_name = 'TestApp', status = 'ENABLED', workspace_name = 'TestApp',
            secret_ref = 'slack/development-workspace', updated_at = now()
        WHERE id = seeded_integration_id
          AND (display_name, status, workspace_name, secret_ref) IS DISTINCT FROM
              ('TestApp', 'ENABLED', 'TestApp', 'slack/development-workspace');
    END IF;

    SELECT id INTO seeded_channel_id
    FROM channels
    WHERE integration_id = seeded_integration_id
      AND external_channel_id = slack_channel_id
    FOR UPDATE;
    IF seeded_channel_id IS NULL THEN
        INSERT INTO channels (
            integration_id, external_channel_id, name, customer_id,
            ignored, grouping_strategy, active
        ) VALUES (
            seeded_integration_id, slack_channel_id, 'new-channel', seeded_customer_id,
            FALSE, 'SLACK_ROOT_THREAD', TRUE
        ) RETURNING id INTO seeded_channel_id;
        channel_action := 'created';
    ELSE
        UPDATE channels
        SET name = 'new-channel', customer_id = seeded_customer_id,
            ignored = FALSE, grouping_strategy = 'SLACK_ROOT_THREAD', active = TRUE
        WHERE id = seeded_channel_id
          AND (name, customer_id, ignored, grouping_strategy, active) IS DISTINCT FROM
              ('new-channel', seeded_customer_id, FALSE, 'SLACK_ROOT_THREAD', TRUE);
    END IF;

    INSERT INTO usi_local_slack_seed_result (sort_order, entity, id, action)
    VALUES (1, 'customer', seeded_customer_id, customer_action),
           (2, 'integration', seeded_integration_id, integration_action),
           (3, 'channel', seeded_channel_id, channel_action);
END;
$seed$;

SELECT entity || '|' || id::text || '|' || action
FROM usi_local_slack_seed_result
ORDER BY sort_order;

COMMIT;
 OR slack_channel_id !~ '^[CG][A-Z0-9]+    IF to_regclass('public.customers') IS NULL
            OR to_regclass('public.integrations') IS NULL
            OR to_regclass('public.channels') IS NULL THEN
        RAISE EXCEPTION 'Local application schema is missing; start pnpm local:api to run Flyway first';
    END IF;

    SELECT id INTO seeded_customer_id
    FROM customers
    WHERE lower(external_ref) = 'slack-test'
    FOR UPDATE;
    IF seeded_customer_id IS NULL THEN
        SELECT id, external_ref INTO seeded_customer_id, existing_external_ref
        FROM customers
        WHERE lower(name) = lower('Slack Test Customer')
        FOR UPDATE;
        IF seeded_customer_id IS NOT NULL
                AND existing_external_ref IS NOT NULL
                AND lower(existing_external_ref) <> 'slack-test' THEN
            RAISE EXCEPTION 'Slack Test Customer already belongs to another external reference';
        END IF;
    END IF;
    IF seeded_customer_id IS NULL THEN
        INSERT INTO customers (name, external_ref)
        VALUES ('Slack Test Customer', 'slack-test')
        RETURNING id INTO seeded_customer_id;
        customer_action := 'created';
    ELSE
        IF EXISTS (
            SELECT 1 FROM customers
            WHERE lower(name) = lower('Slack Test Customer') AND id <> seeded_customer_id
        ) THEN
            RAISE EXCEPTION 'Slack Test Customer name belongs to another customer';
        END IF;
        UPDATE customers
        SET name = 'Slack Test Customer', external_ref = 'slack-test', active = TRUE,
            updated_at = now()
        WHERE id = seeded_customer_id
          AND (name, external_ref, active) IS DISTINCT FROM
              ('Slack Test Customer', 'slack-test', TRUE);
    END IF;

    SELECT id INTO seeded_integration_id
    FROM integrations
    WHERE provider = 'SLACK' AND workspace_external_id = slack_team_id
      AND status <> 'DISABLED'
    FOR UPDATE;
    IF seeded_integration_id IS NULL THEN
        SELECT id INTO seeded_integration_id
        FROM integrations
        WHERE provider = 'SLACK' AND workspace_external_id = slack_team_id
        ORDER BY created_at, id
        LIMIT 1
        FOR UPDATE;
    END IF;
    IF seeded_integration_id IS NULL THEN
        INSERT INTO integrations (
            provider, display_name, status, health,
            workspace_external_id, workspace_name, secret_ref
        ) VALUES (
            'SLACK', 'TestApp', 'ENABLED', 'UNKNOWN',
            slack_team_id, 'TestApp', 'slack/development-workspace'
        ) RETURNING id INTO seeded_integration_id;
        integration_action := 'created';
    ELSE
        UPDATE integrations
        SET display_name = 'TestApp', status = 'ENABLED', workspace_name = 'TestApp',
            secret_ref = 'slack/development-workspace', updated_at = now()
        WHERE id = seeded_integration_id
          AND (display_name, status, workspace_name, secret_ref) IS DISTINCT FROM
              ('TestApp', 'ENABLED', 'TestApp', 'slack/development-workspace');
    END IF;

    SELECT id INTO seeded_channel_id
    FROM channels
    WHERE integration_id = seeded_integration_id
      AND external_channel_id = slack_channel_id
    FOR UPDATE;
    IF seeded_channel_id IS NULL THEN
        INSERT INTO channels (
            integration_id, external_channel_id, name, customer_id,
            ignored, grouping_strategy, active
        ) VALUES (
            seeded_integration_id, slack_channel_id, 'new-channel', seeded_customer_id,
            FALSE, 'SLACK_ROOT_THREAD', TRUE
        ) RETURNING id INTO seeded_channel_id;
        channel_action := 'created';
    ELSE
        UPDATE channels
        SET name = 'new-channel', customer_id = seeded_customer_id,
            ignored = FALSE, grouping_strategy = 'SLACK_ROOT_THREAD', active = TRUE
        WHERE id = seeded_channel_id
          AND (name, customer_id, ignored, grouping_strategy, active) IS DISTINCT FROM
              ('new-channel', seeded_customer_id, FALSE, 'SLACK_ROOT_THREAD', TRUE);
    END IF;

    INSERT INTO usi_local_slack_seed_result (sort_order, entity, id, action)
    VALUES (1, 'customer', seeded_customer_id, customer_action),
           (2, 'integration', seeded_integration_id, integration_action),
           (3, 'channel', seeded_channel_id, channel_action);
END;
$seed$;

SELECT entity || '|' || id::text || '|' || action
FROM usi_local_slack_seed_result
ORDER BY sort_order;

COMMIT;
 THEN
        RAISE EXCEPTION 'Invalid Slack sandbox workspace/channel identifiers';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(
        'usi-local-slack-sandbox-seed:' || slack_team_id || ':' || slack_channel_id, 0));
    IF to_regclass('public.customers') IS NULL
            OR to_regclass('public.integrations') IS NULL
            OR to_regclass('public.channels') IS NULL THEN
        RAISE EXCEPTION 'Local application schema is missing; start pnpm local:api to run Flyway first';
    END IF;

    SELECT id INTO seeded_customer_id
    FROM customers
    WHERE lower(external_ref) = 'slack-test'
    FOR UPDATE;
    IF seeded_customer_id IS NULL THEN
        SELECT id, external_ref INTO seeded_customer_id, existing_external_ref
        FROM customers
        WHERE lower(name) = lower('Slack Test Customer')
        FOR UPDATE;
        IF seeded_customer_id IS NOT NULL
                AND existing_external_ref IS NOT NULL
                AND lower(existing_external_ref) <> 'slack-test' THEN
            RAISE EXCEPTION 'Slack Test Customer already belongs to another external reference';
        END IF;
    END IF;
    IF seeded_customer_id IS NULL THEN
        INSERT INTO customers (name, external_ref)
        VALUES ('Slack Test Customer', 'slack-test')
        RETURNING id INTO seeded_customer_id;
        customer_action := 'created';
    ELSE
        IF EXISTS (
            SELECT 1 FROM customers
            WHERE lower(name) = lower('Slack Test Customer') AND id <> seeded_customer_id
        ) THEN
            RAISE EXCEPTION 'Slack Test Customer name belongs to another customer';
        END IF;
        UPDATE customers
        SET name = 'Slack Test Customer', external_ref = 'slack-test', active = TRUE,
            updated_at = now()
        WHERE id = seeded_customer_id
          AND (name, external_ref, active) IS DISTINCT FROM
              ('Slack Test Customer', 'slack-test', TRUE);
    END IF;

    SELECT id INTO seeded_integration_id
    FROM integrations
    WHERE provider = 'SLACK' AND workspace_external_id = slack_team_id
      AND status <> 'DISABLED'
    FOR UPDATE;
    IF seeded_integration_id IS NULL THEN
        SELECT id INTO seeded_integration_id
        FROM integrations
        WHERE provider = 'SLACK' AND workspace_external_id = slack_team_id
        ORDER BY created_at, id
        LIMIT 1
        FOR UPDATE;
    END IF;
    IF seeded_integration_id IS NULL THEN
        INSERT INTO integrations (
            provider, display_name, status, health,
            workspace_external_id, workspace_name, secret_ref
        ) VALUES (
            'SLACK', 'TestApp', 'ENABLED', 'UNKNOWN',
            slack_team_id, 'TestApp', 'slack/development-workspace'
        ) RETURNING id INTO seeded_integration_id;
        integration_action := 'created';
    ELSE
        UPDATE integrations
        SET display_name = 'TestApp', status = 'ENABLED', workspace_name = 'TestApp',
            secret_ref = 'slack/development-workspace', updated_at = now()
        WHERE id = seeded_integration_id
          AND (display_name, status, workspace_name, secret_ref) IS DISTINCT FROM
              ('TestApp', 'ENABLED', 'TestApp', 'slack/development-workspace');
    END IF;

    SELECT id INTO seeded_channel_id
    FROM channels
    WHERE integration_id = seeded_integration_id
      AND external_channel_id = slack_channel_id
    FOR UPDATE;
    IF seeded_channel_id IS NULL THEN
        INSERT INTO channels (
            integration_id, external_channel_id, name, customer_id,
            ignored, grouping_strategy, active
        ) VALUES (
            seeded_integration_id, slack_channel_id, 'new-channel', seeded_customer_id,
            FALSE, 'SLACK_ROOT_THREAD', TRUE
        ) RETURNING id INTO seeded_channel_id;
        channel_action := 'created';
    ELSE
        UPDATE channels
        SET name = 'new-channel', customer_id = seeded_customer_id,
            ignored = FALSE, grouping_strategy = 'SLACK_ROOT_THREAD', active = TRUE
        WHERE id = seeded_channel_id
          AND (name, customer_id, ignored, grouping_strategy, active) IS DISTINCT FROM
              ('new-channel', seeded_customer_id, FALSE, 'SLACK_ROOT_THREAD', TRUE);
    END IF;

    INSERT INTO usi_local_slack_seed_result (sort_order, entity, id, action)
    VALUES (1, 'customer', seeded_customer_id, customer_action),
           (2, 'integration', seeded_integration_id, integration_action),
           (3, 'channel', seeded_channel_id, channel_action);
END;
$seed$;

SELECT entity || '|' || id::text || '|' || action
FROM usi_local_slack_seed_result
ORDER BY sort_order;

COMMIT;
