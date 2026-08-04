-- Corrections are reversal-only. An unpaid charge is deactivated (it stays on the record, inactive);
-- a paid one is corrected by a credit note, which is a separate document, never an edit.

alter table acc_charge add column active boolean not null default true;

create table acc_credit_note (
  credit_note_id uuid primary key,
  workspace_id   uuid not null,
  charge_id      uuid not null references acc_charge,
  tenancy_id     uuid not null,
  amount         numeric not null,
  reason         text not null,
  issued_on      date not null
);

create index acc_credit_note_workspace_idx on acc_credit_note (workspace_id);
create index acc_credit_note_charge_idx    on acc_credit_note (charge_id);
