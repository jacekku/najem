-- Every read of a hold is scoped by workspace: activeHolds and releaseHold both filter
-- (workspace_id, contact_id), and the primary key leads with contact_id, so neither was served
-- by an index on its leading column. A scoping predicate on an unindexed column never fails --
-- it just degrades as an agency's data grows, which no test notices.
create index contacts_retention_hold_by_contact
  on contacts_retention_hold (workspace_id, contact_id);
