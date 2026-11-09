-- V3 (ticket #8 follow-up, 10-AUTH-CLERK section 9, AD-94). Forward-only (AD-54).
-- The Google or Apple user id behind a Clerk account, so that sign-in could move away from Clerk later and every
-- person still be matched to their row. Both columns are NULL until Clerk reports the linked account (user.updated).
-- An opaque provider id, not an email; it goes with the row when the account is deleted.
ALTER TABLE users ADD COLUMN auth_provider text CHECK (auth_provider IN ('google', 'apple'));
ALTER TABLE users ADD COLUMN auth_subject text CHECK (length(auth_subject) BETWEEN 1 AND 255);
ALTER TABLE users ADD CONSTRAINT users_auth_link_both_or_none
  CHECK ((auth_provider IS NULL) = (auth_subject IS NULL));
-- A plain index on purpose: a duplicate must never turn a webhook into an error that Clerk retries forever. The
-- export check before a move looks for duplicates.
CREATE INDEX ON users (auth_provider, auth_subject) WHERE auth_subject IS NOT NULL;

COMMENT ON COLUMN users.auth_subject IS 'Google or Apple user id from Clerk external_accounts. Opaque. Used only to match people if sign-in moves away from Clerk.';
