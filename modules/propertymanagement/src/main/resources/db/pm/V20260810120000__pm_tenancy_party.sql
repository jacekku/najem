-- Who is on a tenancy, projected.
--
-- The link is not new and this table does not invent it. ReserveTenancy has carried
-- tenantContactIds since the command existed, Tenancy.reserve refuses a reservation that names no
-- tenant, and TenantAddedToTenancy / TenantRemovedFromTenancy keep the aggregate's list current.
-- What was missing is that pm_tenancy never projected any of it, so the only way to answer "who
-- lives here" for a whole workspace was to rebuild every Tenancy aggregate in it.
--
-- The hole this closes is wider than a missing column. TenancyService.addTenant and removeTenant
-- appended to the stream and touched no projection at all -- there was no derived copy for them to
-- fall out of step with, and now that there is, they write it.
--
-- Contact ids only. PM references contacts by id and holds no PII (ReserveTenancy's own comment
-- says so); the name belongs to the contacts module and joining the two is the composition root's
-- job, the same division TenancyLabels already works under.
create table pm_tenancy_party (
  tenancy_id   uuid not null,
  contact_id   uuid not null,
  -- 'TENANT' | 'GUARANTOR'. Written from the Java enum's own name(), never from caller input.
  role         text not null,
  workspace_id uuid not null,
  -- role is IN the key on purpose. The web layer refuses to name one person as both tenant and
  -- guarantor on one reservation (DuplicatePartyException), but the domain does not -- so a
  -- narrower key of (tenancy_id, contact_id) would encode a rule the aggregate has never enforced,
  -- and the projection would start failing on a state PM considers legal. A projection is not the
  -- place to introduce an invariant the record does not have.
  primary key (tenancy_id, contact_id, role)
);

create index pm_tenancy_party_by_tenancy on pm_tenancy_party (workspace_id, tenancy_id);
