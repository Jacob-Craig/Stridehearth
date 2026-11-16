-- V17: The Administrator and their Invites (ADR-0011, #134).
--
-- The first account registered on a Server is its Administrator, who adds anyone else to a closed Server with
-- single-use Invites that expire after 7 days. On an existing Server the earliest-registered account becomes the
-- Administrator. app_user has no registration time, but its id is an identity column handed out in registration
-- order, so the lowest id is the earliest account. An empty Server gets no Administrator here: its first sign-up
-- becomes one.

ALTER TABLE app_user ADD COLUMN administrator BOOLEAN NOT NULL DEFAULT false;

UPDATE app_user SET administrator = true WHERE id = (SELECT min(id) FROM app_user);

-- A Server has at most one Administrator.
CREATE UNIQUE INDEX app_user_one_administrator ON app_user (administrator) WHERE administrator;

-- An Invite stores only a SHA-256 hash of its token: the link itself is shown once, to the Administrator who
-- created it, so a copy of the database cannot be used to join the Server. An Invite is pending until it is used,
-- revoked or past expires_at, and is never both used and revoked.
CREATE TABLE invite (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_by BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    used_by BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT invite_used_or_revoked CHECK (used_at IS NULL OR revoked_at IS NULL),
    CONSTRAINT invite_expires_after_creation CHECK (expires_at > created_at)
);

CREATE INDEX idx_invite_created_by ON invite (created_by);
