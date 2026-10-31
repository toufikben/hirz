-- Accompanied outings (P6, design §12.2 LEASH_BREAK): a guardian takes a person out for a few hours; the guardian's
-- phone reports where it is and which of the person's tags it hears. Nothing here outlives the outing's purpose.
CREATE TABLE outings (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id         uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    guardian_member  uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    starts_at        timestamptz NOT NULL,
    ends_at          timestamptz NOT NULL,
    ended_at         timestamptz,
    last_ping_at     timestamptz,
    last_lat         double precision,
    last_lon         double precision,
    together_at      timestamptz,
    raised_at        timestamptz
);
CREATE INDEX outings_active ON outings (asset_id) WHERE ended_at IS NULL;
