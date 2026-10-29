-- The family code word, AES-GCM encrypted in the server (key HIRZ_DATA_KEY); never stored in clear (design §5.10).
ALTER TABLE circles ADD COLUMN code_word_enc bytea;
ALTER TABLE circles ADD COLUMN country text NOT NULL DEFAULT 'DZ';

-- A member confirmed this number works for their circle (wilaya); per circle, so one family never vouches for another.
CREATE TABLE emergency_confirmations (
    circle_id  uuid NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    country    text NOT NULL,
    number     text NOT NULL,
    member_id  uuid REFERENCES members(id) ON DELETE SET NULL,
    at         timestamptz NOT NULL,
    PRIMARY KEY (circle_id, number),
    FOREIGN KEY (country, number) REFERENCES emergency_numbers (country, number) ON DELETE CASCADE
);
