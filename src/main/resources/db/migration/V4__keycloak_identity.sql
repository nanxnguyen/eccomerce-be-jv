ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE users ADD COLUMN keycloak_subject VARCHAR(255);
CREATE UNIQUE INDEX uk_users_keycloak_subject ON users(keycloak_subject);
