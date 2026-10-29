-- "Are you OK?" requests and their answers (design §6.2); an unanswered one becomes NO_RESPONSE_CHECKIN after 5 minutes.
CREATE TABLE checkins (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id     uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    sent_by      uuid REFERENCES members(id) ON DELETE SET NULL,
    sent_at      timestamptz NOT NULL,
    answered_at  timestamptz,
    answer       text CHECK (answer IN ('ok', 'help')),
    raised_at    timestamptz
);
CREATE INDEX checkins_unanswered ON checkins (sent_at) WHERE answered_at IS NULL AND raised_at IS NULL;
