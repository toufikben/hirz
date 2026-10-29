-- Escalation ladder (design §5): one state row per P0/P1 incident, an append-only log, and the SMS outbox.
CREATE TABLE escalation_state (
    incident_id    uuid PRIMARY KEY REFERENCES incidents(id) ON DELETE CASCADE,
    data           jsonb NOT NULL,
    next_check_at  timestamptz NOT NULL
);
CREATE INDEX escalation_state_due ON escalation_state (next_check_at);

CREATE TABLE escalation_log (
    id             bigserial PRIMARY KEY,
    incident_id    uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    kind           text NOT NULL,
    member_id      uuid REFERENCES members(id) ON DELETE SET NULL,
    contact_phone  text,
    data           jsonb NOT NULL DEFAULT '{}'::jsonb,
    at             timestamptz NOT NULL
);

ALTER TABLE circle_members ADD COLUMN phone text;

CREATE TABLE outbound_sms (
    id           bigserial PRIMARY KEY,
    circle_id    uuid NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    incident_id  uuid REFERENCES incidents(id) ON DELETE SET NULL,
    member_id    uuid REFERENCES members(id) ON DELETE SET NULL,
    to_phone     text NOT NULL,
    body         text NOT NULL,
    created_at   timestamptz NOT NULL,
    taken_at     timestamptz,
    sent_at      timestamptz,
    result       text,
    error        text
);
