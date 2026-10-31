-- Herds (P6, design §1 groups): asset_groups of kind 'herd' with one leader, a weekly market window and the owner's
-- Eid al-Adha date. An animal is in one herd at a time.
ALTER TABLE asset_groups ADD COLUMN leader_asset uuid REFERENCES assets(id) ON DELETE SET NULL;
ALTER TABLE asset_groups ADD COLUMN market_window jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE asset_groups ADD COLUMN eid_date date;
ALTER TABLE asset_group_members ADD COLUMN kind text;
UPDATE asset_group_members m SET kind = g.kind FROM asset_groups g WHERE g.id = m.group_id;
ALTER TABLE asset_group_members ALTER COLUMN kind SET NOT NULL;
CREATE UNIQUE INDEX asset_group_members_one_herd ON asset_group_members (asset_id) WHERE kind = 'herd';
