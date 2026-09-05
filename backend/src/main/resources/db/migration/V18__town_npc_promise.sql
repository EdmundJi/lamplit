-- Distinct from town_npc_memory's rolling summary: a promise needs to be recalled precisely
-- and asked about exactly once, not folded into a periodic prose digest.
CREATE TABLE town_npc_promise (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    npc_code VARCHAR(32) NOT NULL,
    content VARCHAR(200) NOT NULL,
    made_at DATETIME(3) NOT NULL,
    asked_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_town_npc_promise_pending (user_id, npc_code, asked_at, made_at),
    CONSTRAINT fk_town_npc_promise_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
