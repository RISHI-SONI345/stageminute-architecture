-- V2 (ticket #8, sign-in with Clerk). Forward-only (AD-54). No DEFAULT now(): times come from the one injected Clock.

-- F-009: a new account has level and style only after PATCH /me; a returning account already has them, and its
-- server values win. NULL = the person has not chosen yet.
ALTER TABLE users ADD COLUMN onboarded_at timestamptz;

-- Clerk webhooks can arrive out of order (10-AUTH-CLERK section 3, 03 F-092): a user.updated older than the last
-- one applied is ignored.
ALTER TABLE users ADD COLUMN clerk_updated_at timestamptz;

-- F-086, 06 section 1: "5 new accounts per IP a day". A daily cap lives in the database. Only a keyed hash of the
-- address is stored (IdentifierHasher), never the address, and rows go after two days.
CREATE TABLE ip_new_accounts (
  ip_hash   bytea NOT NULL,
  day       date NOT NULL,
  accounts  int NOT NULL CHECK (accounts > 0),
  PRIMARY KEY (ip_hash, day)
);
CREATE INDEX ON ip_new_accounts (day);               -- 2-day purge job

COMMENT ON TABLE ip_new_accounts IS 'Kept 2 days. A keyed hash of the sign-up IP address and a count, so the 5-per-IP-a-day limit survives a restart. No address.';
