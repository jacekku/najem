create table events (
  global_seq  bigserial primary key,
  stream_id   uuid not null,
  stream_type text not null,
  version     bigint not null,
  event_type  text not null,
  payload     jsonb not null,
  occurred_at timestamptz not null default now(),
  unique (stream_id, version)
);

create table outbox (
  id           bigserial primary key,
  event_type   text not null,
  payload      jsonb not null,
  created_at   timestamptz not null default now(),
  published_at timestamptz
);
