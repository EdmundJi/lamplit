-- Private, authored resident story progress. No rewards or relayed social facts.
CREATE TABLE town_story_progress (
    town_user_id BIGINT UNSIGNED NOT NULL,
    npc_code VARCHAR(32) NOT NULL,
    stage TINYINT NOT NULL DEFAULT 0,
    revision INT NOT NULL DEFAULT 0,
    paused TINYINT(1) NOT NULL DEFAULT 0,
    participation VARCHAR(12) NOT NULL DEFAULT 'UNDECIDED',
    started_at DATETIME(3) NULL,
    updated_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (town_user_id, npc_code),
    CONSTRAINT fk_town_story_user FOREIGN KEY (town_user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_town_story_npc CHECK (npc_code IN ('GUIDE', 'POSTMAN')),
    CONSTRAINT chk_town_story_stage CHECK (stage BETWEEN 0 AND 4),
    CONSTRAINT chk_town_story_participation CHECK (participation IN ('UNDECIDED', 'JOINED', 'OBSERVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
