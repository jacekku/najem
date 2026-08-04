-- Money that has not come to rest is what the reconciliation screen is actually about. Suspense is
-- derived rather than stored -- a payment is waiting when it still holds unallocated money and
-- nobody has judged it to be something other than a tenant's payment -- so there is no second
-- source of truth that can disagree with the payments themselves.

alter table acc_payment add column non_tenant_reason text;
alter table acc_payment add column classified_on    date;

-- The suspense query filters on what is still unresolved, per workspace.
create index acc_payment_suspense_idx on acc_payment (workspace_id, status);
