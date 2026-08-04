-- A payment settles charges, not a charge, and it may settle one of them only in part. The boolean
-- said whether a charge was done; it could not say how much of it was.

alter table acc_charge add column allocated_amount numeric not null default 0;
update acc_charge set allocated_amount = amount where allocated;

-- What a payment has not come to rest on is the tenant's credit. It stays on the payment rather
-- than being pushed onto a charge they do not owe yet.
alter table acc_payment add column unallocated_amount numeric;
update acc_payment set unallocated_amount = case when status = 'allocated' then 0 else amount end;
alter table acc_payment alter column unallocated_amount set not null;

-- One row per zloty coming to rest: which payment settled which charge, and how much of it.
create table acc_allocation (
  allocation_id uuid primary key,
  workspace_id  uuid not null,
  payment_id    uuid not null references acc_payment,
  charge_id     uuid not null references acc_charge,
  tenancy_id    uuid not null,
  component     text not null,
  amount        numeric not null,
  allocated_on  date not null
);

create index acc_allocation_payment_idx   on acc_allocation (workspace_id, payment_id);
create index acc_allocation_charge_idx    on acc_allocation (workspace_id, charge_id);
create index acc_allocation_tenancy_idx   on acc_allocation (workspace_id, tenancy_id);
