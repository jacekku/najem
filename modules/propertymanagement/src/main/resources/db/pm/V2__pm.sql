create table pm_unit (
  unit_id     uuid primary key,
  property_id uuid not null,
  name        text not null,
  base_rent   numeric not null
);
