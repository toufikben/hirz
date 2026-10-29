-- A signal is "evaluated" once every listener (rules engine, live hub) ran without error.
-- A retried batch re-runs listeners for stored-but-unevaluated signals, so a crash between commit and
-- evaluation cannot silently swallow an SOS (P1 review #3).
ALTER TABLE positions ADD COLUMN evaluated_at timestamptz;
UPDATE positions SET evaluated_at = server_time;
