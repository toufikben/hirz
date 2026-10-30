-- Every remote engine stop or resume attempt, refused or sent (design §12.3).
CREATE TABLE engine_commands (
    id           bigserial PRIMARY KEY,
    asset_id     uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    member_id    uuid REFERENCES members(id) ON DELETE SET NULL,
    incident_id  uuid REFERENCES incidents(id) ON DELETE SET NULL,
    action       text NOT NULL CHECK (action IN ('stop', 'resume')),
    outcome      text NOT NULL CHECK (outcome IN ('sent', 'refused')),
    reason       text,
    at           timestamptz NOT NULL
);
CREATE INDEX engine_commands_asset ON engine_commands (asset_id, at DESC);
