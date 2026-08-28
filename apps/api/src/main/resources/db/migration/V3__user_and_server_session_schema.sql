-- USI-62 / E04-T01
-- Canonical user identity plus Flyway-owned Spring Session JDBC schema.

CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    email varchar(320) NOT NULL,
    display_name varchar(200) NOT NULL,
    password_hash varchar(255),
    role varchar(16) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    valid_from timestamptz,
    valid_until timestamptz,
    last_login_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_users_email_normalized CHECK (
        email = lower(btrim(email))
        AND char_length(email) BETWEEN 3 AND 320
    ),
    CONSTRAINT ck_users_display_name CHECK (
        char_length(btrim(display_name)) BETWEEN 1 AND 200
    ),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT ck_users_validity_window CHECK (
        valid_from IS NULL
        OR valid_until IS NULL
        OR valid_from < valid_until
    ),
    CONSTRAINT ck_users_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX ux_users_email_case_insensitive
    ON users ((lower(email)));

-- Keep this schema compatible with Spring Session JDBC's PostgreSQL contract.
-- Spring Boot schema initialization is disabled; Flyway is the sole schema owner.
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BYTEA NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK
        FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION(PRIMARY_ID)
        ON DELETE CASCADE
);
