-- Closing a P0 (or "found safe" without proof) needs a second guardian, or the only guardian's second tap (design §5.7).
CREATE TABLE close_requests (
    incident_id   uuid PRIMARY KEY REFERENCES incidents(id) ON DELETE CASCADE,
    requested_by  uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    reason        text NOT NULL,
    at            timestamptz NOT NULL,
    expires_at    timestamptz NOT NULL
);
CREATE INDEX close_requests_expiry ON close_requests (expires_at);

-- Post-close review; one row per reviewer, counted per rule for the false-alarm budget (design §5.9).
CREATE TABLE incident_reviews (
    incident_id  uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    member_id    uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    false_alarm  boolean NOT NULL,
    note         text,
    at           timestamptz NOT NULL,
    PRIMARY KEY (incident_id, member_id)
);
