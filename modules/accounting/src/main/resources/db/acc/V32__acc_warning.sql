-- Compliance warnings are DATA, not log lines: written in the same transaction as the charge that
-- raised them, so a warning can never survive a rollback nor be lost to a restart.

create table acc_warning (
  warning_id   uuid primary key,
  workspace_id uuid not null,
  tenancy_id   uuid not null,
  kind         text not null,
  detail       text not null,
  raised_at    timestamptz not null default now(),
  seen         boolean not null default false
);

create index acc_warning_unseen_idx on acc_warning (workspace_id, seen);
