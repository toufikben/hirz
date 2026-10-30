-- GSM trackers through Traccar (design §3, §12.5.1). The IMEI is the source's device_uid; one active source per IMEI.
ALTER TABLE sources ADD COLUMN device_uid text;
ALTER TABLE sources ADD COLUMN model text;
ALTER TABLE sources ADD COLUMN sim_phone text;
ALTER TABLE sources ADD COLUMN traccar_device_id bigint;
CREATE UNIQUE INDEX sources_active_device_uid ON sources (device_uid) WHERE revoked_at IS NULL AND device_uid IS NOT NULL;

-- A claim proves control: it activates only on a report that arrives after it was made, within its 30 minutes.
CREATE TABLE tracker_claims (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id           uuid NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    imei               text NOT NULL,
    model              text NOT NULL,
    sim_phone          text,
    traccar_device_id  bigint,
    created_by         uuid REFERENCES members(id) ON DELETE SET NULL,
    created_at         timestamptz NOT NULL,
    expires_at         timestamptz NOT NULL,
    activated_at       timestamptz,
    closed_at          timestamptz,
    source_id          uuid REFERENCES sources(id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX tracker_claims_open_imei ON tracker_claims (imei) WHERE closed_at IS NULL;
