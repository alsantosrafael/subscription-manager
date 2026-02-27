-- The version column was used for JPA @Version optimistic locking.
-- All concurrent write paths now use atomic JPQL UPDATE queries with
-- hand-crafted WHERE guards (CAS pattern), making the version column redundant.
ALTER TABLE subscriptions DROP COLUMN IF EXISTS version;

