-- Tracker and vehicle fields of the signal contract (design §3), stored as reported; null means "not reported".
ALTER TABLE positions ADD COLUMN external_power boolean;
ALTER TABLE positions ADD COLUMN ignition boolean;
ALTER TABLE positions ADD COLUMN motion boolean;
