-- Tiers 2-4 of the matching ladder. The tier is stored with the suggestion because a tier-2 guess
-- and a tier-1 certainty are not worth the same confidence from the manager confirming them.

alter table acc_suggestion add column tier integer not null default 1;

-- Tier 3: the account a tenancy has been seen paying from, learned when a manager confirms a match.
-- Keyed by workspace as well as account -- a remembered payer is one agency's knowledge, and an
-- account resolving to a tenancy in someone else's books is a tenancy-boundary breach.
create table acc_payer_account (
  workspace_id      uuid not null,
  counterparty_iban text not null,
  tenancy_id        uuid not null,
  learned_from      uuid not null,
  learned_on        date not null,
  primary key (workspace_id, counterparty_iban)
);
