-- One recent photo per asset for the incident sheet and the police pack (design §5.8); JPEG only, ≤ 512 KB.
CREATE TABLE asset_photos (
    asset_id    uuid PRIMARY KEY REFERENCES assets(id) ON DELETE CASCADE,
    jpeg        bytea NOT NULL CHECK (octet_length(jpeg) <= 524288),
    updated_at  timestamptz NOT NULL
);

-- What the child wears today; shown only on the same local day.
ALTER TABLE assets ADD COLUMN today_clothes text;
ALTER TABLE assets ADD COLUMN today_clothes_at timestamptz;
