-- Institutions (P9): a school, a transport company or an elders' home is a circle of kind 'institution'. A family shares
-- a child with it by consent, limited to the institution's hours and ending with the school year; the family's circle
-- stays the owner and decides everything about the child.
ALTER TABLE circles ADD COLUMN kind text NOT NULL DEFAULT 'family' CHECK (kind IN ('family', 'institution'));

ALTER TABLE asset_consents ADD COLUMN scope text NOT NULL DEFAULT 'custody' CHECK (scope IN ('custody', 'institution'));
ALTER TABLE asset_consents ADD COLUMN windows jsonb;        -- [ActiveWindow]; null or empty: always
ALTER TABLE asset_consents ADD COLUMN expires_at timestamptz;

-- Whether a consent lets the other circle see the asset at [at]: before its end, and inside one of its windows
-- (Africa/Algiers local time; ISO days 1 = Monday … 7 = Sunday; minutes from midnight, end exclusive).
CREATE FUNCTION consent_live(windows jsonb, expires timestamptz, at timestamptz) RETURNS boolean
LANGUAGE sql STABLE AS $$
    SELECT (expires IS NULL OR at < expires)
       AND (windows IS NULL OR jsonb_array_length(windows) = 0 OR EXISTS (
            SELECT 1 FROM jsonb_array_elements(windows) w
            WHERE (w -> 'daysOfWeek') @> to_jsonb(EXTRACT(ISODOW FROM at AT TIME ZONE 'Africa/Algiers')::int)
              AND (EXTRACT(HOUR FROM at AT TIME ZONE 'Africa/Algiers') * 60 + EXTRACT(MINUTE FROM at AT TIME ZONE 'Africa/Algiers'))
                  >= (w ->> 'startMinute')::int
              AND (EXTRACT(HOUR FROM at AT TIME ZONE 'Africa/Algiers') * 60 + EXTRACT(MINUTE FROM at AT TIME ZONE 'Africa/Algiers'))
                  < (w ->> 'endMinute')::int))
$$;
