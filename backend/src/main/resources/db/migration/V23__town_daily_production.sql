-- Freeze the inputs shared by the daytime API and offline simulation.
ALTER TABLE town_npc_mood
    ADD COLUMN affinity_to_player DOUBLE NOT NULL DEFAULT 0.15,
    ADD COLUMN day_plan JSON NULL;

CREATE TABLE town_daily_production (
    town_user_id BIGINT UNSIGNED NOT NULL,
    local_date DATE NOT NULL,
    stage VARCHAR(24) NOT NULL,
    PRIMARY KEY (town_user_id, local_date, stage),
    CONSTRAINT fk_town_daily_production_user FOREIGN KEY (town_user_id) REFERENCES sys_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Explicit reply identity: a timestamp is not a correlation key.
ALTER TABLE town_confidant_thread
    ADD COLUMN reply_to_id BIGINT UNSIGNED NULL,
    ADD UNIQUE KEY uk_town_confidant_reply (reply_to_id);
-- Preserve the old delivery interpretation for already answered historical letters.
UPDATE town_confidant_thread r
JOIN (SELECT * FROM (SELECT o.id AS out_id,
      (SELECT MIN(i.id) FROM town_confidant_thread i WHERE i.user_id=o.user_id
       AND i.direction='IN' AND i.written_at>o.written_at) AS in_id
      FROM town_confidant_thread o WHERE o.direction='OUT') legacy) x ON x.in_id=r.id
SET r.reply_to_id=x.out_id
WHERE r.reply_to_id IS NULL;
ALTER TABLE town_confidant_thread ADD COLUMN answered_at DATETIME(3) NULL;
UPDATE town_confidant_thread o
JOIN (SELECT * FROM (SELECT DISTINCT o2.id FROM town_confidant_thread o2
      JOIN town_confidant_thread i ON i.user_id=o2.user_id AND i.direction='IN'
      AND i.written_at>o2.written_at WHERE o2.direction='OUT') legacy) x ON x.id=o.id
SET o.answered_at=o.written_at;

-- Accepted presence samples, never extrapolated into an unobserved trajectory.
CREATE TABLE town_presence_sample (
    user_id BIGINT UNSIGNED NOT NULL,
    sampled_at DATETIME(3) NOT NULL,
    scene VARCHAR(32) NOT NULL,
    x DOUBLE NOT NULL,
    y DOUBLE NOT NULL,
    PRIMARY KEY (user_id, sampled_at, scene),
    CONSTRAINT fk_town_presence_sample_user FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
