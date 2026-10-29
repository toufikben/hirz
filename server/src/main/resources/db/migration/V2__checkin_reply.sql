-- Check-in replies and PIN outcomes are recorded per signal (design §12.3, §6.2).
ALTER TABLE positions ADD COLUMN checkin_reply text CHECK (checkin_reply IN ('ok', 'help'));
