-- A stream is (stream_id, stream_type), not stream_id. Two bounded contexts may name a stream
-- after the same subject -- PM writes (tenancyId, 'Tenancy') and accounting writes
-- (tenancyId, 'TenancyLedger') for the same tenancy -- and each versions from its own history,
-- so both legitimately hold a version 1 under one id.
--
-- The old constraint forbade that. Filtering reads by stream_type without this change would move
-- the failure rather than remove it: each module would compute its next version from its own
-- events, collide on (stream_id, version), and surface as ConcurrencyException -- which names a
-- concurrent writer that does not exist and invites a retry that fails identically forever.
--
-- Safe on live data with no backfill: the old constraint is strictly stronger than the new one,
-- so no existing pair of rows can violate it. Streams written while identity was shared keep
-- non-contiguous per-type versions (PM at 1,2,5 and accounting at 3,4 for one tenancy). That is
-- harmless -- load orders by version and appends land above each type's own maximum -- and it
-- must NOT be tidied by renumbering: rewriting versions rewrites history, and the gaps are the
-- honest record of the period when the two streams shared one counter.

alter table events drop constraint events_stream_id_version_key;
alter table events add constraint events_stream_identity_version_key
  unique (stream_id, stream_type, version);
