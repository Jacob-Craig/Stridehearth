-- V20: an athlete's dated Thresholds, the Threshold Proposals awaiting their confirmation, and their time zone
-- (ADR-0015, #34).
-- athlete_threshold: one row per kind and effective date, the value in force from that date in the athlete's time
-- zone (bpm for MAX_HR, RESTING_HR and LTHR; m/s for THRESHOLD_PACE; W for CRITICAL_POWER). Before a kind's first
-- entry, that entry applies backwards.
CREATE TABLE athlete_threshold (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    kind VARCHAR(32) NOT NULL,
    effective_date DATE NOT NULL,
    value DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_athlete_threshold_kind_date UNIQUE (user_id, kind, effective_date)
);

-- athlete_threshold_proposal: a value another feature suggests; never an entry until the athlete accepts it. One row
-- per source and kind: a newer proposal replaces the older one, and a DISMISSED row suppresses close re-proposals.
CREATE TABLE athlete_threshold_proposal (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    kind VARCHAR(32) NOT NULL,
    value DOUBLE PRECISION NOT NULL,
    effective_date DATE NOT NULL,
    source VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'DISMISSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_athlete_threshold_proposal_source UNIQUE (user_id, kind, source)
);

-- The athlete's IANA time zone, which their local dates are counted in; NULL means UTC until the SPA sets it.
ALTER TABLE app_user ADD COLUMN time_zone VARCHAR(64);
