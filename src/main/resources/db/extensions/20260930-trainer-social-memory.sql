--liquibase formatted sql

--changeset codex:20260930-trainer-social-memory
CREATE TABLE IF NOT EXISTS trainer_social_incidents (
    incident_id CHAR(36) NOT NULL PRIMARY KEY,
    bot_name VARCHAR(32) NOT NULL,
    actor_character_id INT NULL,
    kind VARCHAR(24) NOT NULL,
    evidence INT NOT NULL,
    item_id INT NULL,
    observed_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    INDEX idx_trainer_incident_bot_actor (bot_name, actor_character_id, expires_at_ms)
);

CREATE TABLE IF NOT EXISTS trainer_social_memory (
    bot_name VARCHAR(32) NOT NULL,
    actor_character_id INT NOT NULL,
    suspicion INT NOT NULL,
    losses INT NOT NULL,
    first_observed_at_ms BIGINT NOT NULL,
    last_observed_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    last_kind VARCHAR(24) NOT NULL,
    PRIMARY KEY (bot_name, actor_character_id),
    INDEX idx_trainer_memory_expiry (expires_at_ms)
);

CREATE TABLE IF NOT EXISTS trainer_venue_meso_ops (
    operation_id CHAR(36) NOT NULL PRIMARY KEY,
    round_id CHAR(36) NOT NULL,
    actor_character_id INT NOT NULL,
    delta INT NOT NULL,
    balance_after INT NOT NULL,
    created_at_ms BIGINT NOT NULL,
    INDEX idx_trainer_venue_meso_round (round_id),
    INDEX idx_trainer_venue_meso_actor (actor_character_id, created_at_ms)
);
