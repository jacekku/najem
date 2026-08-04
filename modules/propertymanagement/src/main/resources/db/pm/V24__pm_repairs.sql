-- Repairs hang off the physical asset, never off the tenancy: a flat's repair history has to
-- outlive the tenant who reported the leak.
create table pm_repair (
  repair_id           uuid primary key,
  workspace_id        uuid not null,
  scope               text not null,
  asset_id            uuid not null,
  description         text not null,
  caused_by_tenancy   uuid,
  statutory_duty_hint text not null,
  reported_on         date not null,
  completed_on        date
);

-- Partial index: the question a manager asks is "what is still open on this flat".
create index pm_repair_open_idx on pm_repair (asset_id) where completed_on is null;
