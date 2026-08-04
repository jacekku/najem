-- A deposit whose cap could not be checked has no multiplier, and that is not the same fact as a
-- multiplier of zero.
--
-- The multiple is of the czynsz. A tenancy declaring no czynsz has no multiple to record, and the
-- column stored 0 for it -- indistinguishable, to every later reader, from a genuinely computed
-- zero. The reader that matters is valorization at return (art. 6 ust. 4), which works from this
-- same base: given 0 it would compute a valorized deposit of nothing and return it as a fact.
--
-- Null says "absent". The DEPOSIT_CAP_UNCHECKABLE warning raised at activation says why.
alter table acc_deposit alter column multiplier drop not null;

update acc_deposit set multiplier = null where rent_at_charge = 0;
