-- Hirz core schema (design §7 and the §12 nucleus tables).
-- PostGIS types resolve through public/extensions; Supabase installs extensions in "extensions".
SET search_path TO ${flyway:defaultSchema}, public, extensions;
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE members (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    firebase_uid  text NOT NULL UNIQUE,
    email         text,
    display_name  text,
    lang          text NOT NULL DEFAULT 'ar' CHECK (lang IN ('ar', 'fr', 'en')),
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE circles (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name            text NOT NULL,
    retention_days  int  NOT NULL DEFAULT 30 CHECK (retention_days BETWEEN 1 AND 365),
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE circle_members (
    circle_id   uuid NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    member_id   uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    role        text NOT NULL CHECK (role IN ('owner', 'guardian', 'viewer', 'contact', 'temporary')),
    priority    int  NOT NULL DEFAULT 100,
    expires_at  timestamptz,
    added_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (circle_id, member_id)
);

CREATE TABLE invites (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    circle_id          uuid NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    code_hash          text NOT NULL UNIQUE,
    role               text NOT NULL CHECK (role IN ('guardian', 'viewer', 'temporary')),
    member_expires_at  timestamptz,
    created_by         uuid NOT NULL REFERENCES members(id),
    expires_at         timestamptz NOT NULL,
    used_at            timestamptz,
    used_by            uuid REFERENCES members(id)
);

CREATE TABLE assets (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    circle_id    uuid NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    type         text NOT NULL,
    name         text NOT NULL,
    descriptors  jsonb NOT NULL DEFAULT '{}'::jsonb,
    lifecycle    text NOT NULL DEFAULT 'active'
                 CHECK (lifecycle IN ('active', 'paused', 'maintenance', 'missing', 'recovered', 'archived')),
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE asset_modes (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id  uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    mode      text NOT NULL,
    schedule  jsonb NOT NULL DEFAULT '[]'::jsonb,
    UNIQUE (asset_id, mode)
);

CREATE TABLE mode_switches (
    id         bigserial PRIMARY KEY,
    asset_id   uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    from_mode  text,
    to_mode    text NOT NULL,
    reason     text NOT NULL,
    by_member  uuid REFERENCES members(id),
    at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE asset_links (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kind         text NOT NULL CHECK (kind IN ('carried_in', 'herd_leader', 'drives', 'accompanied_by')),
    from_asset   uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    to_asset     uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    active_from  timestamptz,
    active_to    timestamptz
);

CREATE TABLE asset_groups (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    circle_id  uuid NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    name       text NOT NULL,
    kind       text NOT NULL
);

CREATE TABLE asset_group_members (
    group_id  uuid NOT NULL REFERENCES asset_groups(id) ON DELETE CASCADE,
    asset_id  uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, asset_id)
);

CREATE TABLE sources (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id           uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    kind               text NOT NULL CHECK (kind IN ('companion', 'traccar', 'ble', 'sms')),
    token_hash         text UNIQUE,
    push_token         text,
    capabilities       jsonb NOT NULL DEFAULT '{}'::jsonb,
    device_info        jsonb NOT NULL DEFAULT '{}'::jsonb,
    adult              boolean NOT NULL DEFAULT false,
    consent_at         timestamptz,
    pin_salt           text,
    cancel_pin_hash    text,
    duress_pin_hash    text,
    last_heartbeat_at  timestamptz,
    last_fix_at        timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    revoked_at         timestamptz
);

CREATE TABLE pairing_codes (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id         uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    code_hash        text NOT NULL UNIQUE,
    pin_salt         text,
    cancel_pin_hash  text,
    duress_pin_hash  text,
    created_by       uuid NOT NULL REFERENCES members(id),
    expires_at       timestamptz NOT NULL,
    used_at          timestamptz
);

CREATE TABLE places (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    circle_id     uuid NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    name          text NOT NULL,
    kind          text NOT NULL,
    geom          geography NOT NULL,
    shape         jsonb NOT NULL,
    margin_m      int NOT NULL CHECK (margin_m >= 50),
    active_hours  jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- One row per received signal; heartbeat-only signals have no geography.
CREATE TABLE positions (
    id                  bigserial PRIMARY KEY,
    source_id           uuid NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
    asset_id            uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    client_id           text NOT NULL,
    fix_time            timestamptz,
    server_time         timestamptz NOT NULL,
    geog                geography(Point, 4326),
    accuracy_m          double precision,
    accuracy_estimated  boolean NOT NULL DEFAULT false,
    fix_source          text,
    speed_kmh           double precision,
    heading             double precision,
    battery             int,
    charging            boolean,
    alarms              text[] NOT NULL DEFAULT '{}',
    quality             text NOT NULL DEFAULT 'ok' CHECK (quality IN ('ok', 'impossible_jump'))
);
CREATE UNIQUE INDEX positions_source_client_uq ON positions (source_id, client_id);
CREATE INDEX positions_asset_fix_idx ON positions (asset_id, fix_time DESC);

CREATE TABLE incidents (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id      uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    circle_id     uuid NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    rule          text NOT NULL,
    tier          text NOT NULL CHECK (tier IN ('P0', 'P1', 'P2')),
    state         text NOT NULL DEFAULT 'open' CHECK (state IN ('open', 'closed')),
    silent        boolean NOT NULL DEFAULT false,
    reasons       jsonb NOT NULL DEFAULT '[]'::jsonb,
    owner_member  uuid REFERENCES members(id),
    opened_at     timestamptz NOT NULL,
    claimed_at    timestamptz,
    closed_at     timestamptz,
    close_reason  text,
    closed_by     uuid REFERENCES members(id),
    exported_at   timestamptz
);
CREATE UNIQUE INDEX incidents_one_open_per_rule ON incidents (asset_id, rule) WHERE state = 'open';

CREATE TABLE findings (
    id           bigserial PRIMARY KEY,
    asset_id     uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    rule         text NOT NULL,
    tier         text NOT NULL,
    reasons      jsonb NOT NULL,
    at           timestamptz NOT NULL,
    incident_id  uuid REFERENCES incidents(id) ON DELETE SET NULL
);

CREATE TABLE incident_events (
    id           bigserial PRIMARY KEY,
    incident_id  uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    kind         text NOT NULL,
    member_id    uuid REFERENCES members(id),
    data         jsonb NOT NULL DEFAULT '{}'::jsonb,
    at           timestamptz NOT NULL
);

CREATE TABLE push_tokens (
    token       text PRIMARY KEY,
    member_id   uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    platform    text NOT NULL DEFAULT 'android',
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE deliveries (
    id            bigserial PRIMARY KEY,
    incident_id   uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    member_id     uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    channel       text NOT NULL,
    token         text,
    provider_id   text,
    sent_at       timestamptz,
    delivered_at  timestamptz,
    opened_at     timestamptz,
    error         text
);

CREATE TABLE location_views (
    id         bigserial PRIMARY KEY,
    member_id  uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    asset_id   uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    via        text NOT NULL,
    at         timestamptz NOT NULL
);

CREATE TABLE consents (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id    uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    circle_a    uuid NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    circle_b    uuid NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    scope       text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz,
    revoked_at  timestamptz
);

CREATE TABLE handovers (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id   uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    member_id  uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    starts_at  timestamptz NOT NULL,
    ends_at    timestamptz NOT NULL CHECK (ends_at > starts_at)
);

CREATE TABLE calendars (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    country    text NOT NULL,
    kind       text NOT NULL CHECK (kind IN ('school', 'ramadan', 'holiday', 'market_day', 'eid_adha')),
    name       text NOT NULL,
    starts_on  date NOT NULL,
    ends_on    date NOT NULL
);

CREATE TABLE signal_loss_zones (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    circle_id      uuid REFERENCES circles(id) ON DELETE CASCADE,
    name           text NOT NULL,
    geom           geography NOT NULL,
    max_silence_s  int NOT NULL
);

CREATE TABLE device_ownership_proofs (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id  uuid NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
    method     text NOT NULL CHECK (method IN ('sms_roundtrip', 'device_password', 'qr_pairing')),
    proved_at  timestamptz NOT NULL
);

CREATE TABLE emergency_numbers (
    country      text NOT NULL,
    number       text NOT NULL,
    label        text NOT NULL,
    verified_at  timestamptz,
    PRIMARY KEY (country, number)
);
INSERT INTO emergency_numbers (country, number, label) VALUES
    ('DZ', '1548', 'police_dgsn'),
    ('DZ', '17',   'police'),
    ('DZ', '14',   'civil_protection'),
    ('DZ', '1055', 'gendarmerie'),
    ('DZ', '112',  'emergency'),
    ('DZ', '104',  'missing_child');

CREATE TABLE audit_log (
    id            bigserial PRIMARY KEY,
    circle_id     uuid REFERENCES circles(id) ON DELETE SET NULL,
    actor_member  uuid REFERENCES members(id) ON DELETE SET NULL,
    action        text NOT NULL,
    data          jsonb NOT NULL DEFAULT '{}'::jsonb,
    at            timestamptz NOT NULL DEFAULT now()
);
