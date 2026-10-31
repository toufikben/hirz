-- Night by the real sun (P7): null keeps the type's default (livestock by the sun, people by fixed hours).
ALTER TABLE assets ADD COLUMN night_by_sun boolean;
-- Ramadan: tarawih evenings are not night exits unless the owner turns that off.
ALTER TABLE assets ADD COLUMN ramadan_night_off boolean NOT NULL DEFAULT false;
