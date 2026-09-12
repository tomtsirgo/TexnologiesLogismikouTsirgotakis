-- =========================================================================
-- Cinema Management System -- bootstrap schema (runs on every boot, both the
-- default H2 file-mode profile and the mysql profile).
-- =========================================================================
-- All statements are idempotent (IF NOT EXISTS) because
-- spring.jpa.hibernate.ddl-auto=update already creates/updates these exact
-- tables from the JPA entities before this script runs
-- (spring.jpa.defer-datasource-initialization=true) - this script is a
-- deliberately redundant, explicit mirror of that generated schema, kept in
-- sync by hand, and doubles as a safety net if ddl-auto is ever turned off.
-- The standalone, hand-written MySQL DDL deliverable lives at
-- db/create_tables.sql in the repo root.

-- -------------------------------------------------------------------------
-- Table: users
-- -------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    username                  VARCHAR(100)  NOT NULL UNIQUE,
    password                  VARCHAR(255)  NOT NULL,
    full_name                 VARCHAR(200)  NOT NULL,
    permanent_role            VARCHAR(20)   NOT NULL,          -- USER | ADMIN
    active                    BOOLEAN       NOT NULL DEFAULT FALSE,
    failed_auth_attempts      INT           NOT NULL DEFAULT 0,
    failed_password_attempts  INT           NOT NULL DEFAULT 0,
    created_at                TIMESTAMP     NOT NULL
);

-- -------------------------------------------------------------------------
-- Table: auth_tokens (opaque bearer tokens, one row per issued token)
-- -------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS auth_tokens (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    token_value   VARCHAR(100)  NOT NULL UNIQUE,
    user_id       BIGINT        NOT NULL,
    issued_at     TIMESTAMP     NOT NULL,
    expires_at    TIMESTAMP     NOT NULL,
    revoked       BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_auth_token_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- -------------------------------------------------------------------------
-- Table: programs (Cinema Season)
-- -------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS programs (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(200)  NOT NULL UNIQUE,
    description   VARCHAR(2000) NOT NULL,
    start_date    DATE          NOT NULL,
    end_date      DATE          NOT NULL,
    state         VARCHAR(30)   NOT NULL,   -- CREATED|SUBMISSION|ASSIGNMENT|REVIEW|SCHEDULING|FINAL_SUBMISSION|DECISION|ANNOUNCED
    created_at    TIMESTAMP     NOT NULL,
    -- The creator becomes PROGRAMMER automatically (FR-PRG-06) and can never be
    -- removed from the PROGRAMMERS set (FR-PRG-08), so the identity is stored.
    created_by    BIGINT        NOT NULL,
    CONSTRAINT fk_program_creator FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE CASCADE
);

-- -------------------------------------------------------------------------
-- Table: program_roles (join table: a user's role WITHIN one program)
-- -------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS program_roles (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    program_id  BIGINT       NOT NULL,
    role        VARCHAR(20)  NOT NULL,   -- PROGRAMMER | STAFF
    CONSTRAINT uk_user_program UNIQUE (user_id, program_id),
    CONSTRAINT fk_program_role_user    FOREIGN KEY (user_id)    REFERENCES users (id)    ON DELETE CASCADE,
    CONSTRAINT fk_program_role_program FOREIGN KEY (program_id) REFERENCES programs (id) ON DELETE CASCADE
);

-- -------------------------------------------------------------------------
-- Table: screenings
-- -------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS screenings (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    program_id             BIGINT        NOT NULL,
    submitter_id           BIGINT        NOT NULL,
    handler_id             BIGINT,
    film_title             VARCHAR(300),
    film_cast              VARCHAR(2000),
    film_genres            VARCHAR(500),
    film_duration_minutes  INT,
    auditorium_name        VARCHAR(200),
    start_time             TIMESTAMP,
    end_time               TIMESTAMP,
    state                  VARCHAR(20)   NOT NULL,   -- CREATED|SUBMITTED|REVIEWED|APPROVED|SCHEDULED|REJECTED
    review_score           INT,
    review_comments        VARCHAR(3000),
    approval_notes         VARCHAR(2000),
    rejection_reason       VARCHAR(2000),
    final_submission_date  TIMESTAMP,
    created_at             TIMESTAMP     NOT NULL,
    CONSTRAINT fk_screening_program   FOREIGN KEY (program_id)   REFERENCES programs (id) ON DELETE CASCADE,
    CONSTRAINT fk_screening_submitter FOREIGN KEY (submitter_id) REFERENCES users (id)    ON DELETE CASCADE,
    CONSTRAINT fk_screening_handler   FOREIGN KEY (handler_id)   REFERENCES users (id)    ON DELETE SET NULL
);
