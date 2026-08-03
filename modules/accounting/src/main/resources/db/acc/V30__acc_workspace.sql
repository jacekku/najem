-- Workspace is the hard multi-tenancy boundary: every accounting row belongs to exactly one
-- agency. Skeleton rows predate the ruling and are backfilled to the dev workspace constant.

alter table acc_charge         add column workspace_id uuid;
alter table acc_payment        add column workspace_id uuid;
alter table acc_suggestion     add column workspace_id uuid;
alter table acc_tenancy_status add column workspace_id uuid;

update acc_charge         set workspace_id = '00000000-0000-0000-0000-000000000001' where workspace_id is null;
update acc_payment        set workspace_id = '00000000-0000-0000-0000-000000000001' where workspace_id is null;
update acc_suggestion     set workspace_id = '00000000-0000-0000-0000-000000000001' where workspace_id is null;
update acc_tenancy_status set workspace_id = '00000000-0000-0000-0000-000000000001' where workspace_id is null;

alter table acc_charge         alter column workspace_id set not null;
alter table acc_payment        alter column workspace_id set not null;
alter table acc_suggestion     alter column workspace_id set not null;
alter table acc_tenancy_status alter column workspace_id set not null;

-- A bank line's external id is only unique within the feed it came from, and feeds belong to
-- workspaces: two agencies may see the same id without it being the same payment.
alter table acc_payment drop constraint acc_payment_external_id_key;
alter table acc_payment add constraint acc_payment_workspace_external_id_key
    unique (workspace_id, external_id);

create index acc_charge_workspace_idx         on acc_charge (workspace_id);
create index acc_payment_workspace_idx        on acc_payment (workspace_id);
create index acc_tenancy_status_workspace_idx on acc_tenancy_status (workspace_id);
