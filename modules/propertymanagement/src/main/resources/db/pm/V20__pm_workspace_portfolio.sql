-- Workspace is the hard multi-tenancy boundary (coordinator ruling seq 21).
-- Existing skeleton rows are backfilled with the dev workspace constant so the
-- walking skeleton stays green through the transition (gate seq 22).
alter table pm_unit add column workspace_id uuid;
update pm_unit set workspace_id = '00000000-0000-0000-0000-000000000001' where workspace_id is null;
alter table pm_unit alter column workspace_id set not null;

create index pm_unit_workspace_idx on pm_unit (workspace_id);

create table pm_property (
  property_id  uuid primary key,
  workspace_id uuid not null,
  address      text not null,
  rent_target  numeric
);

create index pm_property_workspace_idx on pm_property (workspace_id);
