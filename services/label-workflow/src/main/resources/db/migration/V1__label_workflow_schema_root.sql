-- STCN-58 / G1: establish the service-owned version/checksum root.
-- The label_workflow database and restricted user are supplied by external bootstrap.
-- M1's starter applies its technical tables first from V0_1 in db/spectrace-platform.
-- Flyway owns the history table; this migration creates no G2 business tables or fixtures.
-- Label versions, declarations, decisions and publication arrive with G2-M4 from V2 in this
-- directory.
SELECT 1;
