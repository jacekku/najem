-- The statutory-deadline lane of the property timeline (art. 62). next_due_on is stored rather
-- than derived so an inspection keeps the deadline it was given if the statutory interval changes.
create table pm_inspection (
  inspection_id uuid primary key,
  workspace_id  uuid not null,
  property_id   uuid not null,
  type          text not null,
  performed_on  date not null,
  next_due_on   date not null,
  report_doc    text,
  findings      text
);

create index pm_inspection_due_idx on pm_inspection (property_id, next_due_on);
