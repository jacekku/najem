-- Market state and the listing reference the manager searches by while on the phone.
-- Planned for V24 with the Unit Board; pulled forward because the Unit aggregate writes
-- them from Task 2 onward and a projection column that lags its writer invites drift.
alter table pm_unit add column market_state text not null default 'INVENTORY';
alter table pm_unit add column listing_ref text;
