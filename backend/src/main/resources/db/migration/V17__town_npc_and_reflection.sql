CREATE TABLE town_npc_message (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    npc_code VARCHAR(32) NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    risk_level VARCHAR(4) NOT NULL DEFAULT 'L0',
    options_json JSON NULL,
    actions_json JSON NULL,
    model_name VARCHAR(64) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_town_npc_message_public_id (public_id),
    KEY idx_town_npc_message_user (user_id, npc_code, id),
    CONSTRAINT fk_town_npc_message_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_town_npc_message_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT chk_town_npc_message_status CHECK (status IN ('COMPLETED', 'BLOCKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE town_npc_memory (
    user_id BIGINT UNSIGNED NOT NULL,
    npc_code VARCHAR(32) NOT NULL,
    summary TEXT NOT NULL,
    message_count INT UNSIGNED NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, npc_code),
    CONSTRAINT fk_town_npc_memory_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE town_quota (
    user_id BIGINT UNSIGNED NOT NULL,
    local_date DATE NOT NULL,
    chat_count INT UNSIGNED NOT NULL DEFAULT 0,
    reflection_count INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, local_date),
    CONSTRAINT fk_town_quota_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE town_reflection (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    local_date DATE NOT NULL,
    greeting TEXT NOT NULL,
    insights JSON NOT NULL,
    model_name VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_town_reflection_public_id (public_id),
    UNIQUE KEY uk_town_reflection_user_date (user_id, local_date),
    CONSTRAINT fk_town_reflection_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
