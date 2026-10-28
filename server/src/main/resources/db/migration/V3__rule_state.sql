-- Per (asset, place) boundary state for the rules engine; survives restarts.
CREATE TABLE asset_rule_state (
    asset_id       uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    place_id       uuid NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    status         text NOT NULL CHECK (status IN ('unknown', 'inside', 'outside')),
    outside_count  int  NOT NULL DEFAULT 0,
    inside_count   int  NOT NULL DEFAULT 0,
    last_fix_ms    bigint,
    PRIMARY KEY (asset_id, place_id)
);
