--liquibase formatted sql
--changeset codex:20260926-quest-custom-data
-- Scripts already use QuestStatus.customData for persistent progress and cooldowns.
ALTER TABLE queststatus ADD COLUMN customData TEXT CHARACTER SET utf8mb4 NULL;
