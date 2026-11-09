-- Ticket #18.1 (issue #82, AD-149; 05 F-082, EC-010): how the server knows a token belongs to a deleted account.
-- Expand only: one nullable column and a unique index. The table had no rows before #18.1 (the Clerk webhook path wrote
-- the user id only).
--   clerk_hash  keyed hash (HMAC-SHA-256 with the server pepper) of the Clerk user id of the deleted account. A token
--               whose Clerk id hashes to a row here is answered `401 account_deleted` and never makes a new account.
--               Clerk gives a person who signs up again a NEW Clerk id, so the new account is not blocked (EC-010).
-- The note is kept 30 days with its row (ticket #28 purges it); the hash cannot be turned back into the Clerk id.
ALTER TABLE deleted_users ADD COLUMN clerk_hash bytea;
CREATE UNIQUE INDEX deleted_users_clerk_hash_key ON deleted_users (clerk_hash) WHERE clerk_hash IS NOT NULL;
COMMENT ON COLUMN deleted_users.clerk_hash IS 'Keyed hash of the deleted account''s Clerk user id; answers 401 account_deleted for its token. Kept 30 days with the row.';
