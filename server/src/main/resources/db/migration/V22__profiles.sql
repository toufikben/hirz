-- Behavior profiles (P5, design §4.4): a person's condition, the owner's per-rule overrides, the night window and the
-- birth date for the age transition. The condition never leaves the guardian app except on the police sheet when ticked.
ALTER TABLE assets ADD COLUMN condition text NOT NULL DEFAULT 'none' CHECK (condition IN ('none', 'dementia', 'autism'));
ALTER TABLE assets ADD COLUMN profile_overrides jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE assets ADD COLUMN night_window jsonb;
ALTER TABLE assets ADD COLUMN birth_date date;
ALTER TABLE assets ADD COLUMN condition_on_sheet boolean NOT NULL DEFAULT false;
