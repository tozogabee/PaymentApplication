-- Optimistic locking for concurrent updates. Hibernate appends "WHERE version = ?" to every UPDATE
-- and increments the value, so a concurrent update against a stale row affects 0 rows and fails
-- (ObjectOptimisticLockingFailureException -> 409) instead of silently overwriting another change.
ALTER TABLE payments ADD COLUMN version BIGINT NOT NULL DEFAULT 0;