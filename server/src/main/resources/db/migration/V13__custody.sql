-- Temporary handovers may mute place exits (design §12.1); SOS, duress and silence are never muted.
ALTER TABLE handovers ADD COLUMN mute_place_exit boolean NOT NULL DEFAULT false;
ALTER TABLE handovers ADD COLUMN created_by uuid REFERENCES members(id) ON DELETE SET NULL;
CREATE INDEX handovers_asset_window ON handovers (asset_id, starts_at, ends_at);

-- Shared custody between two circles needs both owners (design §12.1).
CREATE TABLE circle_link_codes (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    circle_id   uuid NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    code_hash   text NOT NULL UNIQUE,
    created_by  uuid NOT NULL REFERENCES members(id),
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz
);

CREATE TABLE asset_consents (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id      uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    from_circle   uuid NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    to_circle     uuid NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    requested_by  uuid REFERENCES members(id) ON DELETE SET NULL,
    requested_at  timestamptz NOT NULL,
    accepted_by   uuid REFERENCES members(id) ON DELETE SET NULL,
    accepted_at   timestamptz,
    revoked_by    uuid REFERENCES members(id) ON DELETE SET NULL,
    revoked_at    timestamptz,
    CHECK (from_circle <> to_circle)
);
CREATE INDEX asset_consents_to ON asset_consents (to_circle) WHERE revoked_at IS NULL;
