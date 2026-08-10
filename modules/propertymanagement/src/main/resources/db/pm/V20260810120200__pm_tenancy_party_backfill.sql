-- Backfill pm_tenancy_party from the streams that have always carried the answer.
--
-- The table added one migration earlier starts empty, so every tenancy reserved before it existed
-- renders with no tenant at all -- which on the Najmy register is every row an agency already has.
-- PM is event-sourced: TenancyReserved has carried tenantContactIds and guarantorContactIds since
-- the command existed, and TenantAddedToTenancy / TenantRemovedFromTenancy record every change
-- since. The projection is derived, so rebuilding it from the stream is the ordinary thing to do
-- rather than a data fix.
--
-- WHY A WINDOW FUNCTION AND NOT THREE STATEMENTS. Membership is not "reserved, plus adds, minus
-- removes": a contact may be added, removed, and added again, and a blanket delete-what-was-removed
-- would drop a tenant who is currently on the tenancy. What decides is the LAST event naming that
-- (tenancy, contact) pair -- so the events are unioned into one timeline, ordered by global_seq
-- (the store's own total order), and only pairs whose final event is not a removal are inserted.
--
-- Guarantors have no add/remove events, so they only ever appear from TenancyReserved. They still
-- go through the same timeline, which costs nothing and means the query has one shape rather than
-- two.
--
-- Idempotent by `on conflict do nothing`, matching the projection's own writes: re-running this
-- against a table the application has since written to changes nothing.
insert into pm_tenancy_party (tenancy_id, contact_id, role, workspace_id)
select tenancy_id, contact_id, role, workspace_id
from (
  select
    tenancy_id,
    contact_id,
    role,
    workspace_id,
    removed,
    row_number() over (partition by tenancy_id, contact_id, role order by global_seq desc) as recency
  from (
    -- The parties named when the agreement was signed.
    select (e.payload ->> 'tenancyId')::uuid    as tenancy_id,
           contact::uuid                        as contact_id,
           'TENANT'                             as role,
           (e.payload ->> 'workspaceId')::uuid  as workspace_id,
           e.global_seq,
           false                                as removed
    from events e,
         lateral jsonb_array_elements_text(e.payload -> 'tenantContactIds') as contact
    where e.event_type = 'TenancyReserved'

    union all

    select (e.payload ->> 'tenancyId')::uuid,
           contact::uuid,
           'GUARANTOR',
           (e.payload ->> 'workspaceId')::uuid,
           e.global_seq,
           false
    from events e,
         lateral jsonb_array_elements_text(e.payload -> 'guarantorContactIds') as contact
    where e.event_type = 'TenancyReserved'

    union all

    -- The two commands that appended to the stream and wrote no projection at all until now.
    select (e.payload ->> 'tenancyId')::uuid,
           (e.payload ->> 'contactId')::uuid,
           'TENANT',
           (e.payload ->> 'workspaceId')::uuid,
           e.global_seq,
           e.event_type = 'TenantRemovedFromTenancy'
    from events e
    where e.event_type in ('TenantAddedToTenancy', 'TenantRemovedFromTenancy')
  ) timeline
) latest
where recency = 1 and not removed
on conflict do nothing;
