-- Alarm outcomes (P8): what each closed alarm turned out to be. A guided test is never a false alarm.
ALTER TABLE incident_reviews ADD COLUMN outcome text
    CHECK (outcome IN ('real', 'false_device', 'false_rule', 'test', 'unsure'));

-- Was a digest line useful? One answer per member and line; the last one counts.
CREATE TABLE digest_feedback (
    digest_id  uuid NOT NULL REFERENCES digests(id) ON DELETE CASCADE,
    item_index int  NOT NULL CHECK (item_index >= 0),
    member_id  uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    useful     boolean NOT NULL,
    at         timestamptz NOT NULL,
    PRIMARY KEY (digest_id, item_index, member_id)
);
