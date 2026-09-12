-- =========================================================================
-- Cinema Management System -- standalone MySQL database/table creation script
-- =========================================================================
-- This is the deliverable "SQL scripts for database/table generation"
-- referenced by the assignment's implementation-documentation requirements.
-- It is a standalone, hand-written, MySQL-compatible mirror of the schema
-- that Hibernate (spring.jpa.hibernate.ddl-auto=update) generates at runtime
-- from the JPA entities under src/main/java/gr/aegean/cinema/model/entity/.
-- Run it manually against a MySQL 8.x server, e.g.:
--   mysql -u root -p < db/create_tables.sql

CREATE DATABASE IF NOT EXISTS cinema_management
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE cinema_management;

-- -------------------------------------------------------------------------
-- Table: users
-- -------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    username                  VARCHAR(100)  NOT NULL UNIQUE,
    password                  VARCHAR(255)  NOT NULL,          -- always a BCrypt hash, never plain text
    full_name                 VARCHAR(200)  NOT NULL,
    permanent_role            VARCHAR(20)   NOT NULL,          -- USER | ADMIN
    active                    BOOLEAN       NOT NULL DEFAULT FALSE,
    failed_auth_attempts      INT           NOT NULL DEFAULT 0,
    failed_password_attempts  INT           NOT NULL DEFAULT 0,
    created_at                DATETIME      NOT NULL
) ENGINE=InnoDB;

-- -------------------------------------------------------------------------
-- Table: auth_tokens
-- -------------------------------------------------------------------------
-- One row per issued opaque UUID bearer token. A user has at most one
-- currently non-revoked token at any moment (enforced in the service layer:
-- issuing a new token always revokes every previous one of that user first).
-- Deleting a user cascades the deletion of all of their tokens
-- (ON DELETE CASCADE), matching "user deletion removes the account and all
-- its tokens".
-- -------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS auth_tokens (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    token_value   VARCHAR(100)  NOT NULL UNIQUE,
    user_id       BIGINT        NOT NULL,
    issued_at     DATETIME      NOT NULL,
    expires_at    DATETIME      NOT NULL,                      -- issued_at + 24h by default
    revoked       BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_auth_token_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB;

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
    created_at    DATETIME      NOT NULL,
    -- The creator becomes PROGRAMMER automatically (FR-PRG-06) and can never be
    -- removed from the PROGRAMMERS set (FR-PRG-08), so the identity is stored.
    created_by    BIGINT        NOT NULL,
    CONSTRAINT fk_program_creator FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- -------------------------------------------------------------------------
-- Table: program_roles (join table: a user's role WITHIN one program)
-- -------------------------------------------------------------------------
-- uk_user_program enforces "at most one role per program for a given user"
-- at the database level, in addition to the service-layer check.
-- -------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS program_roles (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    program_id  BIGINT       NOT NULL,
    role        VARCHAR(20)  NOT NULL,   -- PROGRAMMER | STAFF
    CONSTRAINT uk_user_program UNIQUE (user_id, program_id),
    CONSTRAINT fk_program_role_user    FOREIGN KEY (user_id)    REFERENCES users (id)    ON DELETE CASCADE,
    CONSTRAINT fk_program_role_program FOREIGN KEY (program_id) REFERENCES programs (id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- -------------------------------------------------------------------------
-- Table: screenings
-- -------------------------------------------------------------------------
-- Film information (title/cast/genres/duration) and auditorium information
-- are stored as flat columns directly on the screening record, per the
-- assignment's entity description (no separate Film/Auditorium entities).
-- submitter_id is mandatory (set once, at creation, and never changed);
-- handler_id starts NULL and is filled in later, during the program's
-- ASSIGNMENT state, by exactly one STAFF member.
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
    start_time             DATETIME,
    end_time               DATETIME,
    state                  VARCHAR(20)   NOT NULL,   -- CREATED|SUBMITTED|REVIEWED|APPROVED|SCHEDULED|REJECTED
    review_score           INT,
    review_comments        VARCHAR(3000),
    approval_notes         VARCHAR(2000),
    rejection_reason       VARCHAR(2000),
    final_submission_date  DATETIME,
    created_at             DATETIME      NOT NULL,
    CONSTRAINT fk_screening_program   FOREIGN KEY (program_id)   REFERENCES programs (id) ON DELETE CASCADE,
    CONSTRAINT fk_screening_submitter FOREIGN KEY (submitter_id) REFERENCES users (id)    ON DELETE CASCADE,
    CONSTRAINT fk_screening_handler   FOREIGN KEY (handler_id)   REFERENCES users (id)    ON DELETE SET NULL
) ENGINE=InnoDB;

-- -------------------------------------------------------------------------
-- Seed accounts (optional). Passwords below are BCrypt hashes of:
--   admin1  -> AdminPass1!   (ADMIN, active)
--   alice01 -> UserPass1!    (USER, active)
-- -------------------------------------------------------------------------
INSERT INTO users (username, password, full_name, permanent_role, active, failed_auth_attempts, failed_password_attempts, created_at)
VALUES ('admin1', '$2a$10$wz/Vyn1mnslPHffFV.CFueaqu.CyzzkAZyua85Z5ml4j0I0UzzST.', 'System Administrator', 'ADMIN', TRUE, 0, 0, NOW())
ON DUPLICATE KEY UPDATE username = username;

INSERT INTO users (username, password, full_name, permanent_role, active, failed_auth_attempts, failed_password_attempts, created_at)
VALUES ('alice01', '$2a$10$Jeks6kGsbg1G.Q6wOdR5JuwZRwf7zwScnOpMbi7WFAch/nEIyRU.m', 'Alice Papadopoulou', 'USER', TRUE, 0, 0, NOW())
ON DUPLICATE KEY UPDATE username = username;
