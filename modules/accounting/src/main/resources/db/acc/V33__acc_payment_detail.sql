-- A statement line carries more than the four fields Phase 0 kept. The counterparty is the only
-- thing the matching ladder can work with when the tenant types no reference, and direction decides
-- whether money arrived at all -- amounts are always positive, so the sign says nothing.

alter table acc_payment
  add column counterparty_name text,
  add column counterparty_iban text,
  add column bank_reference    text,
  add column value_date        date,
  add column direction         text not null default 'CRDT',
  add column currency          text not null default 'PLN';

-- Tier 3 looks a payer up by the account they paid from.
create index acc_payment_counterparty_idx on acc_payment (workspace_id, counterparty_iban);
