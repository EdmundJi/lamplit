-- 成长小镇社会模拟：NPC 档案、可传播的事实、限知的知识表、关系图（含玩家节点、隐藏的
-- regard/regard_kind）、每日情绪，以及 M4（活动/请柬/迁徙/信件/树洞）的表结构。
-- 一次建全（plan.md §3.1「改表最便宜的时机就是建表那次」）：town_bond 从一开始就带
-- regard/regard_kind，town_npc 从一开始就带 town_user_id（对应 plan 里的 home_user_id）与
-- settled_at。本轮只建 M4 的表，不写业务逻辑。

-- 1.1 town_npc — NPC 档案，一人一镇：每个用户一整套独立的 18 个 NPC。
CREATE TABLE town_npc (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    town_user_id BIGINT UNSIGNED NOT NULL,
    npc_code VARCHAR(32) NOT NULL,
    display_name VARCHAR(32) NOT NULL,
    layer TINYINT NOT NULL,
    sprite VARCHAR(32) NOT NULL,
    dimension VARCHAR(16) NULL,
    share_drive DOUBLE NOT NULL,
    curiosity DOUBLE NOT NULL,
    interests JSON NOT NULL,
    quirks JSON NOT NULL,
    settled_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_town_npc_public_id (public_id),
    UNIQUE KEY uk_town_npc_town_code (town_user_id, npc_code),
    CONSTRAINT fk_town_npc_user FOREIGN KEY (town_user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_town_npc_layer CHECK (layer IN (1, 2, 3)),
    CONSTRAINT chk_town_npc_share_drive CHECK (share_drive BETWEEN 0 AND 1),
    CONSTRAINT chk_town_npc_curiosity CHECK (curiosity BETWEEN 0 AND 1),
    CONSTRAINT chk_town_npc_dimension CHECK (dimension IS NULL OR dimension IN ('KNOWLEDGE', 'HEALTH', 'CAREER', 'RELATIONSHIP', 'WELLBEING'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 1.2 town_fact — 可传播的事实原子。payload 已经过 TownNpcPerception 模糊化，绝不含原始
-- 数字、任务标题或具体时刻（backend 单测断言这一点，见 TownFactTest 一类）。
CREATE TABLE town_fact (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    town_user_id BIGINT UNSIGNED NOT NULL,
    subject_kind VARCHAR(8) NOT NULL,
    subject_ref VARCHAR(32) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    dimension VARCHAR(16) NULL,
    payload JSON NOT NULL,
    occurred_on DATE NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    origin_town_user_id BIGINT UNSIGNED NULL,
    no_relay TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_town_fact_public_id (public_id),
    UNIQUE KEY uk_town_fact_dedup (town_user_id, subject_kind, subject_ref, kind, occurred_on),
    KEY idx_town_fact_user_date (town_user_id, occurred_on),
    CONSTRAINT fk_town_fact_user FOREIGN KEY (town_user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_town_fact_origin_user FOREIGN KEY (origin_town_user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_town_fact_subject_kind CHECK (subject_kind IN ('PLAYER', 'NPC')),
    CONSTRAINT chk_town_fact_dimension CHECK (dimension IS NULL OR dimension IN ('KNOWLEDGE', 'HEALTH', 'CAREER', 'RELATIONSHIP', 'WELLBEING'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 1.3 town_npc_knowledge — 谁知道什么（限知核心）。fact_id 级联删除：事实没了，知道过它的
-- 记录也一起清掉。
CREATE TABLE town_npc_knowledge (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    town_user_id BIGINT UNSIGNED NOT NULL,
    npc_code VARCHAR(32) NOT NULL,
    fact_id BIGINT UNSIGNED NOT NULL,
    learned_on DATE NOT NULL,
    learned_at DATETIME(3) NOT NULL,
    learned_from VARCHAR(32) NULL,
    hops INT NOT NULL DEFAULT 0,
    salience DOUBLE NOT NULL,
    retold_text VARCHAR(255) NULL,
    no_relay TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_town_npc_knowledge_public_id (public_id),
    UNIQUE KEY uk_town_knowledge (town_user_id, npc_code, fact_id),
    KEY idx_town_knowledge_npc_salience (town_user_id, npc_code, salience),
    CONSTRAINT fk_town_knowledge_fact FOREIGN KEY (fact_id) REFERENCES town_fact (id) ON DELETE CASCADE,
    CONSTRAINT chk_town_knowledge_hops CHECK (hops >= 0),
    CONSTRAINT chk_town_knowledge_salience CHECK (salience BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 1.4 town_bond — 关系图，玩家也是节点。行是有向的 a -> b：affinity/resonance/meet_count/
-- last_met_at 语义对称，由写入方同时写两个方向保持一致；regard/regard_kind 只对 a -> b 有
-- 意义、有向且隐藏。
--
-- 硬约束（plan §2.5 红线 1）：NPC 绝不对玩家产生 regard —— 任何一端是 PLAYER 的行，regard
-- 必须为 0、regard_kind 必须为 NULL。这里用 CHECK 焊死，而不是指望应用层永远记得。
CREATE TABLE town_bond (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    town_user_id BIGINT UNSIGNED NOT NULL,
    a_kind VARCHAR(8) NOT NULL,
    a_ref VARCHAR(32) NOT NULL,
    b_kind VARCHAR(8) NOT NULL,
    b_ref VARCHAR(32) NOT NULL,
    affinity DOUBLE NOT NULL DEFAULT 0,
    resonance DOUBLE NOT NULL DEFAULT 0,
    meet_count INT NOT NULL DEFAULT 0,
    last_met_at DATETIME(3) NULL,
    regard DOUBLE NOT NULL DEFAULT 0,
    regard_kind VARCHAR(8) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_town_bond_public_id (public_id),
    UNIQUE KEY uk_town_bond (town_user_id, a_kind, a_ref, b_kind, b_ref),
    CONSTRAINT fk_town_bond_user FOREIGN KEY (town_user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_town_bond_a_kind CHECK (a_kind IN ('PLAYER', 'NPC')),
    CONSTRAINT chk_town_bond_b_kind CHECK (b_kind IN ('PLAYER', 'NPC')),
    CONSTRAINT chk_town_bond_affinity CHECK (affinity BETWEEN 0 AND 1),
    CONSTRAINT chk_town_bond_resonance CHECK (resonance BETWEEN 0 AND 1),
    CONSTRAINT chk_town_bond_regard CHECK (regard BETWEEN 0 AND 1),
    CONSTRAINT chk_town_bond_regard_kind CHECK (regard_kind IS NULL OR regard_kind IN ('CRUSH', 'ADMIRE', 'RIVAL', 'OWE', 'MISS')),
    CONSTRAINT chk_town_bond_no_player_regard CHECK (
        (a_kind <> 'PLAYER' AND b_kind <> 'PLAYER') OR (regard = 0 AND regard_kind IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 1.5 town_npc_mood — 每日情绪。
CREATE TABLE town_npc_mood (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    town_user_id BIGINT UNSIGNED NOT NULL,
    npc_code VARCHAR(32) NOT NULL,
    local_date DATE NOT NULL,
    valence DOUBLE NOT NULL,
    energy DOUBLE NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_town_npc_mood_public_id (public_id),
    UNIQUE KEY uk_town_npc_mood (town_user_id, npc_code, local_date),
    CONSTRAINT fk_town_npc_mood_user FOREIGN KEY (town_user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_town_npc_mood_valence CHECK (valence BETWEEN -1 AND 1),
    CONSTRAINT chk_town_npc_mood_energy CHECK (energy BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 1.6 M4 的表：本轮只建表，不写业务（plan §3.1、§4 M4）。

-- town_event — NPC 自主发起的活动。
CREATE TABLE town_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    town_user_id BIGINT UNSIGNED NOT NULL,
    host_npc_code VARCHAR(32) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    venue VARCHAR(16) NOT NULL,
    dimension VARCHAR(16) NULL,
    starts_at DATETIME(3) NOT NULL,
    ends_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_town_event_public_id (public_id),
    KEY idx_town_event_user_starts (town_user_id, starts_at),
    CONSTRAINT fk_town_event_user FOREIGN KEY (town_user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_town_event_dimension CHECK (dimension IS NULL OR dimension IN ('KNOWLEDGE', 'HEALTH', 'CAREER', 'RELATIONSHIP', 'WELLBEING'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- town_invitation — 活动请柬，按 affinity 降序发出，邮递员送达。
CREATE TABLE town_invitation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    town_user_id BIGINT UNSIGNED NOT NULL,
    event_id BIGINT UNSIGNED NOT NULL,
    recipient_kind VARCHAR(8) NOT NULL,
    recipient_ref VARCHAR(32) NOT NULL,
    delivered_at DATETIME(3) NULL,
    read_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_town_invitation_public_id (public_id),
    KEY idx_town_invitation_event (event_id),
    CONSTRAINT fk_town_invitation_user FOREIGN KEY (town_user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_town_invitation_event FOREIGN KEY (event_id) REFERENCES town_event (id) ON DELETE CASCADE,
    CONSTRAINT chk_town_invitation_recipient_kind CHECK (recipient_kind IN ('PLAYER', 'NPC'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- town_migration — 第三层 NPC 在互为好友的小镇之间交换制迁徙的记录。
CREATE TABLE town_migration (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    npc_code VARCHAR(32) NOT NULL,
    from_user_id BIGINT UNSIGNED NOT NULL,
    to_user_id BIGINT UNSIGNED NOT NULL,
    paired_with_npc_code VARCHAR(32) NULL,
    moved_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_town_migration_public_id (public_id),
    KEY idx_town_migration_from (from_user_id, moved_at),
    KEY idx_town_migration_to (to_user_id, moved_at),
    CONSTRAINT fk_town_migration_from_user FOREIGN KEY (from_user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_town_migration_to_user FOREIGN KEY (to_user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- town_letter — 树洞长信 / NPC 短笺 / 活动请柬三轨之外的正式信件对象，全部由邮递员送。
CREATE TABLE town_letter (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    recipient_user_id BIGINT UNSIGNED NOT NULL,
    sender_kind VARCHAR(16) NOT NULL,
    sender_ref VARCHAR(32) NULL,
    kind VARCHAR(16) NOT NULL,
    body TEXT NOT NULL,
    deliver_at DATETIME(3) NOT NULL,
    read_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_town_letter_public_id (public_id),
    KEY idx_town_letter_recipient (recipient_user_id, deliver_at),
    CONSTRAINT fk_town_letter_recipient FOREIGN KEY (recipient_user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_town_letter_sender_kind CHECK (sender_kind IN ('CONFIDANT', 'NPC')),
    CONSTRAINT chk_town_letter_kind CHECK (kind IN ('LONG', 'NOTE', 'INVITE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- town_confidant_thread — 树洞笔友往来。plan §2.7 / §3.4 隔离要求：这张表绝不得对
-- town_fact / town_npc_knowledge（或任何小镇八卦/传播表）建外键，也不共享查询路径——树洞内容
-- 必须完全不进入传播网络，因此这里刻意不引用上面任何一张表，只挂回 sys_user。
CREATE TABLE town_confidant_thread (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    direction VARCHAR(8) NOT NULL,
    body TEXT NOT NULL,
    written_at DATETIME(3) NOT NULL,
    deliver_at DATETIME(3) NULL,
    read_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_town_confidant_thread_public_id (public_id),
    KEY idx_town_confidant_thread_user (user_id, written_at),
    CONSTRAINT fk_town_confidant_thread_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_town_confidant_thread_direction CHECK (direction IN ('OUT', 'IN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
