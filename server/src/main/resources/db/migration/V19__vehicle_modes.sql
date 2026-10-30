-- Vehicle modes (design §12.1): a manual switch, drive authorizations, an armed schedule (asset_modes, mode 'armed'),
-- and the state the resolver last saw. Every effective change is a mode_switches row (V1).
ALTER TABLE assets ADD COLUMN mode_override text CHECK (mode_override IN ('armed', 'disarmed', 'maintenance'));
ALTER TABLE assets ADD COLUMN mode_override_at timestamptz;

CREATE TABLE vehicle_authorizations (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id     uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    member_id    uuid REFERENCES members(id) ON DELETE SET NULL,
    driver_name  text,
    starts_at    timestamptz NOT NULL,
    ends_at      timestamptz NOT NULL CHECK (ends_at > starts_at),
    created_by   uuid REFERENCES members(id) ON DELETE SET NULL,
    revoked_at   timestamptz
);
CREATE INDEX vehicle_authorizations_asset ON vehicle_authorizations (asset_id, ends_at);

CREATE TABLE vehicle_state (
    asset_id        uuid PRIMARY KEY REFERENCES assets(id) ON DELETE CASCADE,
    ignition        boolean,
    ignition_since  timestamptz,
    ignition_at     timestamptz,  -- time of the newest report that carried ignition; older ones never roll it back
    mode            text,
    mode_since      timestamptz,
    mode_reason     text,
    armed_lat       double precision,
    armed_lon       double precision
);
