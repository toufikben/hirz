-- Evening digest (P7, design §7.4): the day's P2 findings gathered into one quiet message per circle at 20:00.
CREATE TABLE digests (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    circle_id  uuid NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    day        date NOT NULL,
    items      jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (circle_id, day)
);
