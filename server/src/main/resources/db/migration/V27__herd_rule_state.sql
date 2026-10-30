-- Herd-wide rule state (P6): recent spread samples for HERD_DISPERSION.
CREATE TABLE herd_rule_state (
    herd_id  uuid PRIMARY KEY REFERENCES asset_groups(id) ON DELETE CASCADE,
    state    jsonb NOT NULL
);
