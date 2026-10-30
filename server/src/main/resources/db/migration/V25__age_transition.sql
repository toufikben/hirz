-- Age transition (P5, design §12.5.6): the owner is told once at 15; at 18 a child's sources pause until the person
-- gives adult consent on their own phone.
ALTER TABLE assets ADD COLUMN age_notice_15_at timestamptz;
ALTER TABLE sources ADD COLUMN paused_reason text;
