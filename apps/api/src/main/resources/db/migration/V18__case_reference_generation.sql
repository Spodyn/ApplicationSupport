-- USI-85 / E07-T02
-- Database-owned, concurrency-safe human-readable Case references.

CREATE SEQUENCE case_reference_seq
    AS bigint
    START WITH 1
    INCREMENT BY 1
    NO CYCLE;

-- Preserve upgrade safety if Cases already exist before this migration is applied.
-- setval(..., false) makes the next nextval() return exactly max(existing)+1.
SELECT setval(
    'case_reference_seq',
    COALESCE((
        SELECT MAX(substring(reference FROM '^CASE-([0-9]+)$')::bigint)
        FROM cases
        WHERE reference ~ '^CASE-[0-9]+$'
    ), 0) + 1,
    false
);

CREATE FUNCTION next_case_reference()
RETURNS varchar(32)
LANGUAGE sql
VOLATILE
AS $$
    SELECT 'CASE-' ||
           CASE
               WHEN value < 100000000 THEN lpad(value::text, 8, '0')
               ELSE value::text
           END
    FROM (SELECT nextval('case_reference_seq') AS value) generated;
$$;

ALTER TABLE cases
    ALTER COLUMN reference SET DEFAULT next_case_reference();

ALTER SEQUENCE case_reference_seq OWNED BY cases.reference;
