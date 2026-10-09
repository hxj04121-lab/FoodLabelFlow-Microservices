-- STCN-49 / G1: establish the service-owned Compliance migration root.
-- The database and its restricted user are supplied by the local/staging bootstrap.
-- Flyway owns the checksum and version history; do not create databases or grants here.
-- This skeleton intentionally adds no business tables, fixtures or cross-service references.
-- Compliance domain migrations (G2) and M1's technical outbox migrations follow at V2+.
SELECT 1;
