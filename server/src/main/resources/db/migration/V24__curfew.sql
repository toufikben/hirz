-- A teen's curfew, minutes from midnight Africa/Algiers (P5, design §12.2 CURFEW).
ALTER TABLE assets ADD COLUMN curfew_minute int CHECK (curfew_minute BETWEEN 0 AND 1439);
