-- BLE tags and the phone relay (P6, design §2). A tag is a source of kind 'ble' on its asset; sightings uploaded by any
-- Hirz phone are kept a week for presence (leash, owner near the car) and never shown to the tag's circle.
CREATE TABLE ble_tags (
    tag_id      text PRIMARY KEY,
    asset_id    uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    source_id   uuid NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
    format      text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ble_sightings (
    id               bigserial PRIMARY KEY,
    tag_id           text NOT NULL REFERENCES ble_tags(tag_id) ON DELETE CASCADE,
    relay_source_id  uuid NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
    seen_at          timestamptz NOT NULL,
    rssi             int NOT NULL
);
CREATE INDEX ble_sightings_tag_relay ON ble_sightings (tag_id, relay_source_id, seen_at DESC);
