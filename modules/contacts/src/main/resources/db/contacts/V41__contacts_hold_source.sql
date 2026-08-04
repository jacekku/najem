-- A hold is raised BY something, and only that something may release it.
--
-- V40 keyed the register (contact_id, reason). A contact who is a tenant or guarantor
-- on two tenancies then had ONE row for "ledger-referenced": the first tenancy to close
-- released it, and the person became erasable while the second tenancy's ledger was
-- still live. No error, no warning — the release simply looked successful.
--
-- source_ref is the justifying subject (a tenancyId, once the b' trigger lands).
-- 'manual' is the source for a hold a manager sets by hand through the REST API,
-- which is every hold that exists today — hence the default on the backfill.
alter table contacts_retention_hold add column source_ref text not null default 'manual';
alter table contacts_retention_hold drop constraint contacts_retention_hold_pkey;
alter table contacts_retention_hold add primary key (contact_id, reason, source_ref);
