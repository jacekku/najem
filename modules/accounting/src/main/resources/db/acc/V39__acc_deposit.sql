-- The deposit as agreed, with the multiple snapshotted at activation. The rent it is a multiple of
-- moves over a tenancy; the multiple in the contract does not, so valorization at return works from
-- the snapshot rather than from today's arithmetic.
--
-- One deposit per tenancy, scoped by workspace like everything else. The settlement columns land in
-- task 9 and are deliberately absent rather than nullable placeholders.

create table acc_deposit (
  deposit_id     uuid primary key,
  workspace_id   uuid not null,
  tenancy_id     uuid not null,
  charge_id      uuid not null references acc_charge,
  legal_form     text not null,
  nominal_amount numeric not null,
  rent_at_charge numeric not null,
  multiplier     numeric not null,
  state          text not null,
  charged_on     date not null,
  unique (workspace_id, tenancy_id)
);
