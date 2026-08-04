-- Backs AttentionListsQuery.insuranceExpiring. Written when a DocType.INSURANCE_POLICY document
-- is attached; null means no policy on file, which is itself worth a manager's attention.
alter table pm_tenancy add column insurance_valid_to date;
