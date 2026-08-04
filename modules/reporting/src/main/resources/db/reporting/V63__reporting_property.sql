-- Properties, so occupancy can be grouped and labelled without reaching into PM's tables.
-- Only what a board needs to name and group by; PM owns everything else about a property.
create table reporting_property (
  property_id  uuid primary key,
  workspace_id uuid not null,
  address      text not null
);

create index reporting_property_by_workspace on reporting_property (workspace_id);
