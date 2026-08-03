-- PII lookaside: the ONLY table in the system holding personal data.
-- Right-to-be-forgotten = delete the row here; event streams stay untouched.
-- Workspace (= agency) is the hard tenancy boundary: every read filters on it.
create table contacts_person (
  contact_id            uuid primary key,
  workspace_id          uuid not null,
  given_name            text not null,
  surname               text not null,
  email                 text,
  phone                 text,
  lawful_basis          text not null,
  info_clause_served_at date,
  retain_until          date
);

-- Deliberately NOT unique: families share an email, and the same person may
-- legitimately re-enter as a new lead. This index serves the "do we already
-- know this person?" lookup, which a manager judges.
create index contacts_person_by_email on contacts_person (workspace_id, email);
create index contacts_person_by_retention on contacts_person (workspace_id, retain_until);

-- Tombstone: records THAT an erasure happened. Holds no personal data.
create table contacts_erasure_log (
  contact_id   uuid primary key,
  workspace_id uuid not null,
  erased_on    date not null
);

-- Lead = contact + interest link to unit(s). Deliberately thin, no CRM.
create table contacts_interest (
  interest_id    uuid primary key,
  workspace_id   uuid not null,
  contact_id     uuid not null,
  unit_id        uuid not null,
  willing_to_pay numeric,
  desired_start  date,
  status         text not null default 'active'
);

create index contacts_interest_by_unit on contacts_interest (workspace_id, unit_id);
create index contacts_interest_by_contact on contacts_interest (workspace_id, contact_id);

-- Erasure gate. Accounting's ~5-6y tax/civil-prescription holds land here.
create table contacts_retention_hold (
  contact_id   uuid not null,
  workspace_id uuid not null,
  reason       text not null,
  set_on       date not null,
  released_on  date,
  primary key (contact_id, reason)
);
