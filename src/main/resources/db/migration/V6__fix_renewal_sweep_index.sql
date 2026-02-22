-- V6: Replace the partial index on next_retry_at (created in V2) with a
-- composite partial index that matches the exact WHERE clause used by
-- findEligibleForRenewal:
--
--   WHERE status = 'ACTIVE'
--     AND auto_renew = true
--     AND expiring_date <= :now
--     AND billing_attempts < :maxAttempts
--     AND next_retry_at <= :now
--
-- The old index covered only next_retry_at, causing a full table scan on
-- the status + auto_renew + expiring_date + billing_attempts filters.
-- The new index uses a partial predicate to keep it as narrow as possible,
-- and INCLUDEs billing_attempts so the planner can satisfy the
-- billing_attempts < :maxAttempts inequality without a heap fetch.

DROP INDEX IF EXISTS idx_sub_renewal_sweep;

CREATE INDEX idx_sub_renewal_sweep
    ON subscriptions (next_retry_at, expiring_date)
    INCLUDE (billing_attempts)
    WHERE status = 'ACTIVE' AND auto_renew = true;

