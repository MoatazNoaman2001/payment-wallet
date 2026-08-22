-- Refresh tokens are opaque random strings, stored hashed the same way passwords are:
-- a database leak must not hand out usable tokens.
--
-- family_id groups every token descended from one login. Refresh tokens are single use;
-- if a revoked one is presented again, either the client or an attacker replayed an old
-- copy and we cannot tell which, so the whole family is revoked and the user re-logs in.

CREATE TABLE refresh_token (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,          -- SHA-256 hex, never the raw token
    family_id  UUID        NOT NULL,
    issued_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX idx_refresh_family ON refresh_token(family_id);
CREATE INDEX idx_refresh_user   ON refresh_token(user_id);

-- Registration only ever grants ROLE_CUSTOMER, so an administrator has to be seeded.
-- Demo credentials, documented as such in the README: admin@paymentwallet.local / admin12345
INSERT INTO app_user (email, phone, password_hash, full_name, status)
VALUES ('admin@paymentwallet.local', '+000000000001',
        '$2a$10$K8vlJMqjj.YbTy.rjRPyFOrdxquovs27whhwQ2qBKa33JhDD8Tzrq',
        'Demo Administrator', 'ACTIVE');

INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id
FROM app_user u, role r
WHERE u.email = 'admin@paymentwallet.local'
  AND r.name IN ('ROLE_ADMIN', 'ROLE_CUSTOMER');
