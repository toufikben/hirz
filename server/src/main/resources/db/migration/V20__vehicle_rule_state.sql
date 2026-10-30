-- Per-vehicle rule state: consecutive moving fixes, the newest fix seen, and one jamming-by-silence flag per silence.
CREATE TABLE vehicle_rule_state (
    asset_id       uuid PRIMARY KEY REFERENCES assets(id) ON DELETE CASCADE,
    moving_count   int NOT NULL DEFAULT 0,
    last_fix_ms    bigint,
    jamming_since  timestamptz
);
