-- V7: Enforce one-subscription-per-user at the DB level.
--
-- Without this constraint, two concurrent POST /v1/subscriptions requests for
-- the same user can both pass the application-level "findByUserId" check
-- (both read zero rows before either commits) and insert two rows — causing
-- IncorrectResultSizeDataAccessException on all subsequent queries.
--
-- The constraint also makes findByUserId's Optional contract safe: the DB
-- guarantees at most one row per user, so Spring Data never throws on
-- multiple results.

ALTER TABLE subscriptions
    ADD CONSTRAINT uk_subscription_user_id UNIQUE (user_id);

