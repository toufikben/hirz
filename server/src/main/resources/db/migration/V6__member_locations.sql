-- Guardian locations exist only while their circle has an open P0/P1 incident, and are deleted at close (design §5.11).
CREATE TABLE member_locations (
    id           bigserial PRIMARY KEY,
    incident_id  uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    member_id    uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    geog         geography(Point, 4326) NOT NULL,
    accuracy_m   double precision,
    at           timestamptz NOT NULL
);
CREATE INDEX member_locations_incident ON member_locations (incident_id, member_id, at DESC);
