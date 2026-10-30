-- Proof of control by challenge (P3 review C1): the claim picks a random reporting interval; it activates only when the
-- device's fixes arrive at that interval, which only someone who can send it the setup SMS can make happen.
ALTER TABLE tracker_claims ADD COLUMN proof_interval_s int NOT NULL DEFAULT 60;
ALTER TABLE tracker_claims ADD COLUMN proof_times timestamptz[] NOT NULL DEFAULT '{}';
-- The backup sync never imports positions from before a source existed (review I3).
CREATE INDEX tracker_claims_created_by ON tracker_claims (created_by, created_at);
