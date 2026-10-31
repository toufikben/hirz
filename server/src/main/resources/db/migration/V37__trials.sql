-- Field trials (P8). An operator opens a trial; a circle's owner joins it with consent and may leave at any time,
-- which deletes the circle's trial data. Trial data is aggregate and pseudonymous: no names, ids or coordinates.
CREATE TABLE trials (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text NOT NULL,
    starts_on   date NOT NULL,
    ends_on     date NOT NULL CHECK (ends_on >= starts_on),
    created_by  text NOT NULL,           -- the operator's uid
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE trial_circles (
    trial_id         uuid NOT NULL REFERENCES trials(id) ON DELETE CASCADE,
    circle_id        uuid NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    consent_at       timestamptz NOT NULL,
    consent_version  int NOT NULL,
    consent_member   uuid NOT NULL REFERENCES members(id),
    left_at          timestamptz,
    pseudonym        text NOT NULL UNIQUE, -- what the operator sees instead of the circle
    PRIMARY KEY (trial_id, circle_id)
);

-- A guided test the family announced: it should ring [rule] on [asset] within the test window.
CREATE TABLE guided_tests (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trial_id    uuid NOT NULL REFERENCES trials(id) ON DELETE CASCADE,
    circle_id   uuid NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    asset_id    uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    rule        text NOT NULL,
    started_at  timestamptz NOT NULL,
    member_id   uuid NOT NULL REFERENCES members(id)
);
CREATE INDEX guided_tests_asset ON guided_tests (asset_id, started_at);

-- Nightly per asset-day aggregates (TrialMetricsJob). Keyed by pseudonyms only.
CREATE TABLE trial_daily (
    trial_id       uuid NOT NULL REFERENCES trials(id) ON DELETE CASCADE,
    circle_pseudo  text NOT NULL,
    asset_pseudo   text NOT NULL,
    day            date NOT NULL,
    live_hours     int NOT NULL DEFAULT 0,
    fresh_hours    int NOT NULL DEFAULT 0,
    findings       jsonb NOT NULL DEFAULT '{}'::jsonb,  -- {"RULE/TIER": count}
    false_p0       int NOT NULL DEFAULT 0,
    false_p1       int NOT NULL DEFAULT 0,
    closed_p0p1    int NOT NULL DEFAULT 0,
    reviewed_p0p1  int NOT NULL DEFAULT 0,
    guided_tests   int NOT NULL DEFAULT 0,
    guided_missed  int NOT NULL DEFAULT 0,
    p0_guided      int NOT NULL DEFAULT 0,
    p0_delivery_ms bigint[] NOT NULL DEFAULT '{}',
    p0_ack_ms      bigint[] NOT NULL DEFAULT '{}',
    digest_rated   int NOT NULL DEFAULT 0,
    digest_useful  int NOT NULL DEFAULT 0,
    PRIMARY KEY (trial_id, asset_pseudo, day)
);
CREATE INDEX trial_daily_circle ON trial_daily (trial_id, circle_pseudo);
