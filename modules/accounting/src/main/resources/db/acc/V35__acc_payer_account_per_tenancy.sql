-- One account may pay for more than one tenancy: a parent guaranteeing two children's flats is the
-- ordinary case. Keyed by account alone, confirming the second flat silently forgot the first, and
-- tier 3 then suggested the wrong flat's charge every month afterwards -- the same shape as the
-- retention-hold collision contacts fixed by adding the source dimension.

alter table acc_payer_account drop constraint acc_payer_account_pkey;
alter table acc_payer_account add primary key (workspace_id, counterparty_iban, tenancy_id);

-- The warning changed meaning with the key. It no longer reports a reassignment the ledger just
-- performed; it reports that an account can no longer identify a tenancy on its own, which is when
-- tier 3 goes quiet for it.
update acc_warning set kind = 'payerAccountAmbiguous' where kind = 'payerAccountReassigned';
