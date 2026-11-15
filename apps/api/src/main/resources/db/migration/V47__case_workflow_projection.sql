-- Keep legacy command writers and older v1 clients compatible during the rollout.
ALTER TABLE cases ADD COLUMN workflow_status text GENERATED ALWAYS AS (
    CASE status
        WHEN 'VERIFICATION' THEN 'OPEN'
        WHEN 'WAITING_FOR_CUSTOMER' THEN 'WAITING_ON_CUSTOMER'
        WHEN 'IGNORED' THEN 'RESOLVED'
        WHEN 'RESOLVED' THEN 'RESOLVED'
        ELSE 'NEW'
    END
) VIRTUAL;
