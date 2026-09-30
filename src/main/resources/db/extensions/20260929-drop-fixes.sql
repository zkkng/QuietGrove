--liquibase formatted sql

--changeset codex:20260929-drop-fixes
-- Keep both v83 Maple Island tutorial variants completable. Quest 1035 uses
-- item 4031802; legacy quest 1018 uses Jr. Sentinel Shellpiece 4000142.
INSERT INTO drop_data (dropperid, itemid, minimum_quantity, maximum_quantity, questid, chance)
SELECT 9300018, 4000142, 1, 1, 1018, 999999
WHERE NOT EXISTS (
    SELECT 1
    FROM drop_data
    WHERE dropperid = 9300018
      AND itemid = 4000142
      AND questid = 1018
);
