-- Which bank account a workspace's money arrives in.
--
-- The statement port used to fetch from one account named in configuration and hand every line to
-- whichever workspace asked for it. Each workspace then matched those lines against its own charges,
-- correctly and independently -- so one transfer could be suggested against a charge in agency A and
-- a charge in agency B, and if both managers accepted, money that exists once was credited twice, in
-- two sets of books, by the ordinary path. Every query was scoped; the composition was not.
--
-- The account is per workspace because the question it answers -- whose money is this? -- is a
-- per-workspace question. A deployment-wide value answers it with one agency's account for every
-- workspace in the system.
--
-- There is no default and no fallback: a workspace with no row here cannot ingest (rule 7). An
-- unconfigured workspace that quietly ingested somebody else's statement is the defect this table
-- exists to remove, so refusing is the whole point rather than an inconvenience.
create table acc_workspace_account (
  workspace_id  uuid primary key,
  iban          text not null,
  registered_on date not null
);

-- One account belongs to one workspace. Without this the old defect is reachable again by
-- registration rather than by configuration: two workspaces naming the same account both ingest
-- every line in it.
create unique index acc_workspace_account_iban_key on acc_workspace_account (iban);
