-- Two facts that "released" was conflating, and they are opposites for occupancy.
--
-- PM frees the unit's calendar both when a reservation is CANCELLED and when a tenancy ENDS. The
-- first never occupied the unit; the second occupied it for its whole run. Excluding both erased
-- the history of every tenancy that ended normally -- the unit would read as never let.
--
-- So: a period counts as occupancy unless it was annulled, or it was released without ever having
-- ended. ended_on records the ending; annulled records a tenancy that should never have existed
-- (the event stays in the stream -- an event store does not forget -- but nobody lived there).
alter table reporting_unit_period add column annulled boolean not null default false;
alter table reporting_unit_period add column ended_on date;
