-- When the asset's first reading beyond a place's margin came (P7): a sparse track confirms an exit hours later, and a
-- routine's expected trip is judged at the moment it began, not at the confirming fix.
ALTER TABLE asset_rule_state ADD COLUMN first_out_ms bigint;
