-- Reporting is pure read-side: no aggregates, no commands, no events of its own.
-- Everything here is derived and rebuildable — set a checkpoint to 0 and the
-- projection reconstructs itself from history. That property is what lets a read
-- model change shape without a data migration.

create table reporting_checkpoint (
  projection_name text primary key,
  last_global_seq bigint not null default 0
);

-- One row per curated fact on a timeline. Denormalised on purpose: a timeline read
-- is one indexed scan, and rebuildability makes the duplication safe.
create table reporting_timeline_entry (
  entry_id     bigserial primary key,
  workspace_id uuid not null,
  level        text not null,   -- 'tenancy' | 'unit' | 'property'
  subject_id   uuid not null,   -- tenancyId | unitId | propertyId
  occurred_on  date not null,
  global_seq   bigint not null, -- ordering tiebreak AND the idempotency key
  kind         text not null,   -- 'tenancy-reserved', 'rent-paid', ...
  summary      text not null,
  detail       jsonb,
  -- Replay must be a no-op, not a duplicate story: a re-applied event conflicts here.
  unique (level, subject_id, global_seq)
);

create index reporting_timeline_by_subject
  on reporting_timeline_entry (workspace_id, level, subject_id, occurred_on, global_seq);
