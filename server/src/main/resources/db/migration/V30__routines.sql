-- Routines (P7, design §1): when an asset is expected at a place. Calendar overrides let a circle correct the built-in
-- school holidays and Ramadan dates, which are estimates.
CREATE TABLE routines (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id            uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    place_id            uuid NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    days                int[] NOT NULL,
    arrive_by_min       int CHECK (arrive_by_min BETWEEN 0 AND 1439),
    dismissal_min       int CHECK (dismissal_min BETWEEN 0 AND 1439),
    ramadan_offset_min  int NOT NULL DEFAULT 0,
    active              boolean NOT NULL DEFAULT true,
    created_by          uuid REFERENCES members(id) ON DELETE SET NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CHECK (arrive_by_min IS NOT NULL OR dismissal_min IS NOT NULL)
);
CREATE INDEX routines_asset ON routines (asset_id);

CREATE TABLE calendar_overrides (
    circle_id  uuid NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    kind       text NOT NULL CHECK (kind IN ('school_day_off', 'school_day_on', 'ramadan_start', 'ramadan_end')),
    day        date NOT NULL,
    PRIMARY KEY (circle_id, kind, day)
);
