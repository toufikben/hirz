-- "Report a problem" (P8): free text, app version and device model. No location column, by design.
CREATE TABLE problem_reports (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    circle_id    uuid NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    member_id    uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    text         text NOT NULL,
    app_version  text NOT NULL,
    device       text NOT NULL,
    at           timestamptz NOT NULL
);
CREATE INDEX problem_reports_circle ON problem_reports (circle_id, at DESC);
