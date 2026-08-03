create table um_workspace (
  workspace_id uuid primary key,
  name         text not null,
  created_on   date not null
);

create table um_user (
  user_id          uuid primary key,
  keycloak_subject uuid not null unique,
  contact_id       uuid,
  registered_on    date not null
);

create table um_membership (
  workspace_id uuid not null references um_workspace,
  user_id      uuid not null references um_user,
  role         text not null,
  joined_on    date not null,
  primary key (workspace_id, user_id)
);

create table um_invitation (
  invitation_id       uuid primary key,
  workspace_id        uuid not null references um_workspace,
  role                text not null,
  token_hash          text not null unique,
  status              text not null,
  invited_by_user_id  uuid not null,
  issued_on           date not null,
  expires_on          date not null,
  accepted_by_user_id uuid
);

-- PII lookaside (decision D3): the invitee's address never enters an event payload
-- and this row is deleted on accept, revoke or expiry.
create table um_invitation_recipient (
  invitation_id uuid primary key references um_invitation,
  email         text not null
);

create index um_membership_user_idx on um_membership (user_id);
create index um_invitation_workspace_idx on um_invitation (workspace_id, status);
