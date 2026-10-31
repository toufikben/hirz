-- Verify first (P7, design §12.2): after repeated false alarms of one rule on one asset, its P1 starts with a quiet
-- push and escalates as usual if nobody confirms. Never a P0. The owner can turn it off for the asset.
ALTER TABLE incidents ADD COLUMN verify_first boolean NOT NULL DEFAULT false;
ALTER TABLE assets ADD COLUMN verify_first_off boolean NOT NULL DEFAULT false;
