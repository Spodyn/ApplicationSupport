-- Slack intake preserves an unknown customer as NULL, never a guessed real customer.
ALTER TABLE cases ALTER COLUMN customer_id DROP NOT NULL;
ALTER TABLE cases ADD CONSTRAINT ck_cases_unassigned_slack
    CHECK (customer_id IS NOT NULL OR provider = 'SLACK');
