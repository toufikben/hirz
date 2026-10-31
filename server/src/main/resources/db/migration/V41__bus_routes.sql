-- Bus routes (P9): a bus's run is a corridor (a path and a width) with ordered stops, on some weekdays between a start
-- and an end (minutes from midnight, Africa/Algiers). Inside that window the bus is in "route" mode.
CREATE TABLE bus_routes (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id    uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    name        text NOT NULL,
    path        jsonb NOT NULL,                 -- [LatLon], at least two
    width_m     double precision NOT NULL CHECK (width_m BETWEEN 20 AND 500),
    days        int[] NOT NULL,                 -- ISO 1 = Monday … 7 = Sunday
    start_min   int NOT NULL CHECK (start_min BETWEEN 0 AND 1439),
    end_min     int NOT NULL CHECK (end_min BETWEEN 1 AND 1440),
    created_by  uuid REFERENCES members(id) ON DELETE SET NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CHECK (start_min < end_min)
);
CREATE INDEX bus_routes_asset ON bus_routes (asset_id);

CREATE TABLE route_stops (
    route_id  uuid NOT NULL REFERENCES bus_routes(id) ON DELETE CASCADE,
    seq       int NOT NULL,
    name      text NOT NULL,
    lat       double precision NOT NULL,
    lon       double precision NOT NULL,
    radius_m  double precision NOT NULL CHECK (radius_m BETWEEN 10 AND 300),
    due_min   int NOT NULL CHECK (due_min BETWEEN 0 AND 1439),
    PRIMARY KEY (route_id, seq)
);
