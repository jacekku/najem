-- What a unit is right now, derived. This is the row a Unit Board renders.
create table reporting_unit_state (
  unit_id      uuid primary key,
  workspace_id uuid not null,
  property_id  uuid not null,
  name         text not null,
  base_rent    numeric,
  -- 'inventory' until someone opens it: PM's Unit starts closed to rent and says so explicitly
  -- only when it changes, so absence of an open/close event is itself the initial state.
  market_state text not null default 'inventory',
  removed      boolean not null default false
);

create index reporting_unit_state_by_property on reporting_unit_state (workspace_id, property_id);

-- The unit's calendar, as PM registers it. Occupancy and vacancy are both READ from this:
-- a vacant span is the absence of a period, never an event, because nothing emits "the unit
-- went empty". Storing vacancy would mean inventing rows for something that never happened.
create table reporting_unit_period (
  unit_id      uuid not null,
  tenancy_id   uuid not null,
  workspace_id uuid not null,
  starts_on    date not null,
  ends_on      date,            -- null = indefinite, occupies the unit until ended
  released     boolean not null default false,
  primary key (unit_id, tenancy_id)
);

create index reporting_unit_period_by_unit on reporting_unit_period (workspace_id, unit_id, starts_on);
