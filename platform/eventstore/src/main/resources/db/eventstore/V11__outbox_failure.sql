-- A handler that throws used to leave its row unmarked, so the next tick re-read the same row
-- first (order by id) and threw again -- every 500ms, forever, with every event behind it
-- undelivered. The system went quiet and looked healthy.
--
-- A failed row is now recorded as data rather than retried blindly: it stops blocking the queue,
-- it says why, and it is visible to anyone who asks. That follows the project's standing rule
-- that warnings are data, not log lines -- a stalled outbox is exactly the condition nobody sees
-- in a log nobody reads.

alter table outbox add column failed_at      timestamptz;
alter table outbox add column failure_reason text;

-- Delivery reads only rows that are neither published nor failed, so this is the index that
-- matters as the table grows; published rows are the overwhelming majority and stay out of it.
create index outbox_pending on outbox (id) where published_at is null and failed_at is null;
