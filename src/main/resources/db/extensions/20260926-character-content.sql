--liquibase formatted sql
--changeset codex:20260926-character-content
CREATE TABLE character_content (
    characterid INT NOT NULL PRIMARY KEY,
    state MEDIUMTEXT NOT NULL,
    CONSTRAINT character_content_owner FOREIGN KEY (characterid) REFERENCES characters(id) ON DELETE CASCADE
) ENGINE=InnoDB;
