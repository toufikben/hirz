-- Night outings the owner expects (Fajr at the mosque), and per-asset state of the P5 people rules.
ALTER TABLE assets ADD COLUMN night_exceptions jsonb NOT NULL DEFAULT '[]'::jsonb;

CREATE TABLE person_rule_state (
    asset_id    uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    rule        text NOT NULL,
    state       jsonb NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (asset_id, rule)
);
