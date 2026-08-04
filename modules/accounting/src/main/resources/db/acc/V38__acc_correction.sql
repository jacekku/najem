-- An allocation that turned out to be wrong is undone, not deleted. The row survives marked as
-- reversed, because what the ledger did before it was corrected is part of the record -- the same
-- reversal-only rule that gives a paid charge a credit note rather than an edit.

alter table acc_allocation add column reversed boolean not null default false;

alter table acc_payment add column reversal_reason text;
alter table acc_payment add column reversed_on     date;
