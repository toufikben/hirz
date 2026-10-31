-- P8 review: P0s that never reached a phone or were never claimed count against latency; outcomes the gate does not
-- accept as decided ("unsure", or "test" with no guided test) are shown, not hidden.
ALTER TABLE trial_daily ADD COLUMN p0_alarms       int NOT NULL DEFAULT 0;
ALTER TABLE trial_daily ADD COLUMN p0_undelivered  int NOT NULL DEFAULT 0;
ALTER TABLE trial_daily ADD COLUMN p0_unclaimed    int NOT NULL DEFAULT 0;
ALTER TABLE trial_daily ADD COLUMN unsure_reviews  int NOT NULL DEFAULT 0;
ALTER TABLE trial_daily ADD COLUMN unmatched_tests int NOT NULL DEFAULT 0;
