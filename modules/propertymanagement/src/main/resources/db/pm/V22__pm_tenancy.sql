create table pm_tenancy (
  tenancy_id        uuid primary key,
  workspace_id      uuid not null,
  unit_id           uuid not null,
  start_date        date not null,
  end_date          date,
  legal_form        text not null,
  monthly_total     numeric not null,
  -- null unless the CONTRACT declares a split; component_split says which, explicitly,
  -- because "no split" and "split with a zero adminFee" are legally different.
  rent              numeric,
  admin_fee         numeric,
  media_advance     numeric,
  component_split   boolean not null,
  rent_day          int not null,
  deposit_amount    numeric,
  payment_reference text not null,
  state             text not null
);

create index pm_tenancy_workspace_idx on pm_tenancy (workspace_id);
create index pm_tenancy_unit_idx on pm_tenancy (unit_id);
