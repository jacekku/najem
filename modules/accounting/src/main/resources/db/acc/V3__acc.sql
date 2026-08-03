create table acc_charge (
  charge_id         uuid primary key,
  tenancy_id        uuid not null,
  component         text not null,
  amount            numeric not null,
  due_date          date not null,
  payment_reference text not null,
  allocated         boolean not null default false
);

create table acc_payment (
  payment_id   uuid primary key,
  external_id  text unique not null,
  amount       numeric not null,
  title        text not null,
  booking_date date not null,
  status       text not null
);

create table acc_suggestion (
  payment_id uuid primary key references acc_payment,
  charge_id  uuid not null references acc_charge
);

create table acc_tenancy_status (
  tenancy_id uuid primary key,
  status     text not null
);
