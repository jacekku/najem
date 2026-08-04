-- Durable timers for the PM process managers. fired_at makes every process idempotent
-- across restarts and repeated sweeps: a due row fires once and stays fired.
create table pm_process_due (
  kind       text not null,
  subject_id uuid not null,
  due_on     date not null,
  fired_at   timestamptz,
  primary key (kind, subject_id)
);

create index pm_process_due_pending_idx on pm_process_due (kind, due_on) where fired_at is null;

alter table pm_tenancy add column activated_on date;
