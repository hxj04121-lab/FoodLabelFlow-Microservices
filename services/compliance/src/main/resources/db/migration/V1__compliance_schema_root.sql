-- STCN-49 / G1: establish the service-owned version/checksum root.
-- The compliance database and restricted user are supplied by external bootstrap.
-- M1's starter applies its technical tables first from V0_1 in db/spectrace-platform.
-- Flyway owns the history table; this migration creates no G2 business tables or fixtures.
-- Future Compliance domain migrations follow from V2 in this directory.
SELECT 1;
