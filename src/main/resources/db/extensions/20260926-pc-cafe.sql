--liquibase formatted sql
--changeset codex:20260926-pc-cafe
CREATE TABLE pc_cafe_progress (
    characterid INT NOT NULL PRIMARY KEY,
    state VARCHAR(2048) NOT NULL
) ENGINE=InnoDB;
