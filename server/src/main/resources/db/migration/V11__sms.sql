-- SMS gateways: a family phone with a SIM that sends the circle's outbound SMS and relays replies (design §12.3.3).
CREATE TABLE gateway_codes (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    circle_id   uuid NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    code_hash   text NOT NULL UNIQUE,
    created_by  uuid NOT NULL REFERENCES members(id),
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz
);

CREATE TABLE gateways (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    circle_id     uuid NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    token_hash    text NOT NULL UNIQUE,
    push_token    text,
    created_at    timestamptz NOT NULL,
    last_seen_at  timestamptz,
    revoked_at    timestamptz
);

-- An item is offered again 60 s after it was taken without a result, at most 3 times.
ALTER TABLE outbound_sms ADD COLUMN attempts int NOT NULL DEFAULT 0;
ALTER TABLE outbound_sms ADD COLUMN gateway_id uuid REFERENCES gateways(id) ON DELETE SET NULL;
CREATE INDEX outbound_sms_pending ON outbound_sms (circle_id, created_at) WHERE sent_at IS NULL;

-- Every received SMS and what it did: claimed, declined, ignored, unknown_sender, no_open_incident.
CREATE TABLE inbound_sms (
    id           bigserial PRIMARY KEY,
    circle_id    uuid NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    gateway_id   uuid REFERENCES gateways(id) ON DELETE SET NULL,
    from_phone   text NOT NULL,
    body         text NOT NULL,
    received_at  timestamptz NOT NULL,
    member_id    uuid REFERENCES members(id) ON DELETE SET NULL,
    incident_id  uuid REFERENCES incidents(id) ON DELETE SET NULL,
    action       text NOT NULL
);
