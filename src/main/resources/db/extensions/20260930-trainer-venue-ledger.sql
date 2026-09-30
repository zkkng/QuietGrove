--liquibase formatted sql

--changeset codex:20260930-trainer-venue-ledger
CREATE TABLE trainer_venue_rounds (
    round_id CHAR(36) NOT NULL PRIMARY KEY,
    venue_map_id INT NOT NULL,
    channel_id INT NOT NULL,
    host_bot_name VARCHAR(32) NOT NULL,
    player_character_id INT NULL,
    tier_mesos INT NOT NULL,
    pot_balance BIGINT NOT NULL DEFAULT 0,
    phase VARCHAR(20) NOT NULL,
    admission_operation_id CHAR(36) NULL,
    refund_operation_id CHAR(36) NULL,
    opened_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    fault_reason VARCHAR(255) NULL,
    INDEX idx_trainer_venue_round_phase (venue_map_id, channel_id, phase),
    INDEX idx_trainer_venue_round_player (player_character_id, opened_at_ms)
);

CREATE TABLE trainer_venue_stock (
    asset_id CHAR(36) NOT NULL PRIMARY KEY,
    venue_map_id INT NOT NULL,
    channel_id INT NOT NULL,
    owner_bot_name VARCHAR(32) NOT NULL,
    item_id INT NOT NULL,
    item_snapshot BLOB NOT NULL,
    estimated_value BIGINT NOT NULL,
    acquisition_operation_id CHAR(36) NOT NULL,
    acquisition_reason VARCHAR(64) NOT NULL,
    state VARCHAR(20) NOT NULL,
    round_id CHAR(36) NULL,
    claimed_by_character_id INT NULL,
    acquired_at_ms BIGINT NOT NULL,
    changed_at_ms BIGINT NOT NULL,
    UNIQUE KEY uq_trainer_venue_stock_acquisition (acquisition_operation_id),
    INDEX idx_trainer_venue_stock_ready (venue_map_id, channel_id, state, estimated_value),
    INDEX idx_trainer_venue_stock_round (round_id, state)
);

CREATE TABLE trainer_venue_asset_ops (
    operation_id CHAR(36) NOT NULL PRIMARY KEY,
    round_id CHAR(36) NOT NULL,
    asset_id CHAR(36) NOT NULL,
    operation_kind VARCHAR(24) NOT NULL,
    actor_character_id INT NULL,
    created_at_ms BIGINT NOT NULL,
    INDEX idx_trainer_venue_asset_round (round_id, asset_id),
    INDEX idx_trainer_venue_asset_actor (actor_character_id, created_at_ms)
);

CREATE TABLE trainer_venue_daily_budget (
    venue_map_id INT NOT NULL,
    channel_id INT NOT NULL,
    day_utc DATE NOT NULL,
    issued_value BIGINT NOT NULL DEFAULT 0,
    issued_units INT NOT NULL DEFAULT 0,
    PRIMARY KEY (venue_map_id, channel_id, day_utc)
);

CREATE TABLE trainer_venue_treasury (
    venue_map_id INT NOT NULL,
    channel_id INT NOT NULL,
    balance BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (venue_map_id, channel_id)
);

CREATE TABLE trainer_venue_treasury_ops (
    operation_id CHAR(36) NOT NULL PRIMARY KEY,
    round_id CHAR(36) NOT NULL,
    venue_map_id INT NOT NULL,
    channel_id INT NOT NULL,
    delta BIGINT NOT NULL,
    reason VARCHAR(24) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    INDEX idx_trainer_venue_treasury_round (round_id)
);
