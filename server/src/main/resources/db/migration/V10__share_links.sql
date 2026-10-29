-- Temporary public links to an open incident's live position (design §5.8). Only the token's SHA-256 is stored.
CREATE TABLE share_links (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id  uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    token_hash   text NOT NULL UNIQUE,
    created_by   uuid REFERENCES members(id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    revoked_at   timestamptz
);
CREATE INDEX share_links_incident ON share_links (incident_id);

-- Every open of a share link; the address is stored hashed.
CREATE TABLE share_views (
    id          bigserial PRIMARY KEY,
    token_hash  text NOT NULL,
    at          timestamptz NOT NULL,
    ip_hash     text,
    user_agent  text
);
CREATE INDEX share_views_token ON share_views (token_hash, at);
