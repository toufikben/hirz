-- P1 minor #17: the silence episode survives restarts; P1 minor #12: any PIN entry raises the rate for 10 minutes.
ALTER TABLE sources ADD COLUMN silence_episode text;
ALTER TABLE sources ADD COLUMN last_pin_at timestamptz;
