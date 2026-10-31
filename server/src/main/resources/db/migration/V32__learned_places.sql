-- Learned places (P7, design §4.5): where an asset regularly stays, learned from its own track each night. Supporting
-- evidence only; replaced at every learning run, so nothing outlives the positions it came from by more than a day.
CREATE TABLE learned_places (
    id         bigserial PRIMARY KEY,
    asset_id   uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    lat        double precision NOT NULL,
    lon        double precision NOT NULL,
    radius_m   double precision NOT NULL,
    days       int NOT NULL,
    visits     int NOT NULL,
    last_seen  timestamptz NOT NULL
);
CREATE INDEX learned_places_asset ON learned_places (asset_id);
