-- Derivation indexes, and the reason they exist.
--
-- Accounting's events carry no workspaceId (najem-build seq 88; the coordinator ruled at seq 91
-- that they will gain one after accounting's task 5). Until then Reporting has to derive the
-- workspace for a ledger fact, and workspace is the hard tenancy boundary -- so the derivation is
-- an explicit, inspectable table rather than a join buried in a projection.
--
-- Chain: PaymentAllocated -> chargeId -> ChargePosted.tenancyId -> TenancyReserved.workspaceId.
-- DELETE BOTH TABLES when accounting's events carry workspaceId; they have no other purpose.

create table reporting_tenancy_index (
  tenancy_id   uuid primary key,
  workspace_id uuid not null,
  unit_id      uuid not null
);

create table reporting_charge_index (
  charge_id  uuid primary key,
  tenancy_id uuid not null
);

-- PaymentAllocated carries no date -- but "rent paid on date B" is exactly what the timeline is
-- for, and the date a manager means is when the money arrived. That lives on PaymentIngested,
-- a different event on the same stream, so it has to be remembered when it goes past.
create table reporting_payment_index (
  payment_id   uuid primary key,
  booking_date date not null
);
