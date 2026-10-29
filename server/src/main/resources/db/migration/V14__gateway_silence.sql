-- One "gateway silent" notice per silence episode (design §12.3.3); reset when the gateway is heard again.
ALTER TABLE gateways ADD COLUMN silent_notified_at timestamptz;
