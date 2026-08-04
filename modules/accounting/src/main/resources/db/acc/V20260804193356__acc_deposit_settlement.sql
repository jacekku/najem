-- Settling the deposit at the end of a tenancy.
--
-- Art. 6 ust. 4 u.o.p.l.: the deposit goes back in the amount corresponding to the agreed multiple
-- of the czynsz in force on the day of return, but never less than the sum actually taken. Both
-- figures are kept rather than only the result, because "you got back what you put in" and "you got
-- back the valorized amount, which happened to equal what you put in" are the same number and
-- different facts -- and only the second can be checked against the statute a year later.
--
-- Deductions are kept apart from the returned amount for the same reason: a tenant who owed nothing
-- and a tenant whose arrears consumed the whole deposit both receive zero, and the difference is the
-- entire dispute.
alter table acc_deposit add column settled_on       date;
alter table acc_deposit add column rent_at_return   numeric(12,2);
alter table acc_deposit add column valorized_amount numeric(12,2);
alter table acc_deposit add column deducted_amount  numeric(12,2);
alter table acc_deposit add column returned_amount  numeric(12,2);

-- What the deposit was used for. One row per charge the deposit paid off, so a disputed deduction
-- can be traced to the obligation it settled rather than appearing as a single unexplained total.
create table acc_deposit_deduction (
  deduction_id uuid primary key,
  workspace_id uuid    not null,
  deposit_id   uuid    not null references acc_deposit(deposit_id),
  charge_id    uuid    not null references acc_charge(charge_id),
  amount       numeric(12,2) not null,
  deducted_on  date    not null
);

create index acc_deposit_deduction_deposit_idx on acc_deposit_deduction (workspace_id, deposit_id);
