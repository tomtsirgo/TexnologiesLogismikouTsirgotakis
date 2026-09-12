-- =========================================================================
-- Cinema Management System -- bootstrap seed data (runs on every boot, after
-- schema.sql/Hibernate schema creation - see spring.jpa.defer-datasource-
-- initialization=true). Every statement is written as an
-- INSERT ... SELECT ... WHERE NOT EXISTS so re-running it against an
-- already-seeded database (e.g. the persistent H2 file-mode default profile,
-- restarted) is a safe no-op instead of a duplicate-key error.
-- =========================================================================
-- Seeded accounts (plain-text passwords shown here ONLY for manual testing;
-- the stored `password` column is always the BCrypt hash of that value,
-- matching PasswordUtil/BCryptPasswordEncoder):
--   admin1   / AdminPass1!  (ADMIN, active)
--   alice01  / UserPass1!   (USER, active)
--   bob2024  / UserPass2!   (USER, active)
--   carol_x  / UserPass3!   (USER, active)
--   davidz9  / UserPass4!   (USER, INACTIVE - demonstrates the "newly
--                            registered account starts inactive" rule)
-- -------------------------------------------------------------------------

INSERT INTO users (username, password, full_name, permanent_role, active, failed_auth_attempts, failed_password_attempts, created_at)
SELECT 'admin1', '$2a$10$wz/Vyn1mnslPHffFV.CFueaqu.CyzzkAZyua85Z5ml4j0I0UzzST.', 'System Administrator', 'ADMIN', TRUE, 0, 0, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'admin1');

INSERT INTO users (username, password, full_name, permanent_role, active, failed_auth_attempts, failed_password_attempts, created_at)
SELECT 'alice01', '$2a$10$Jeks6kGsbg1G.Q6wOdR5JuwZRwf7zwScnOpMbi7WFAch/nEIyRU.m', 'Alice Papadopoulou', 'USER', TRUE, 0, 0, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'alice01');

INSERT INTO users (username, password, full_name, permanent_role, active, failed_auth_attempts, failed_password_attempts, created_at)
SELECT 'bob2024', '$2a$10$0y1MJ5h41lvn.HXnrXyyeO8RMjxz5wD.wn4XIdj.KF6ksjypuOyta', 'Bob Ioannidis', 'USER', TRUE, 0, 0, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'bob2024');

INSERT INTO users (username, password, full_name, permanent_role, active, failed_auth_attempts, failed_password_attempts, created_at)
SELECT 'carol_x', '$2a$10$4KDC5Pu2j02Vdj1pWWPJM.s4nVewt5V.Q5su/a2I2Xo.hnt0j4B/u', 'Carol Nikolaou', 'USER', TRUE, 0, 0, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'carol_x');

INSERT INTO users (username, password, full_name, permanent_role, active, failed_auth_attempts, failed_password_attempts, created_at)
SELECT 'davidz9', '$2a$10$tjEePjcMGA1ZIzY26gOLZetPlDtmuc35BbqhurgS09dx7qgvT1F16', 'David Zervas', 'USER', FALSE, 0, 0, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'davidz9');
