-- 成就系统：成就定义 + 用户解锁记录
CREATE TABLE achievement (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(80) NOT NULL,
    body VARCHAR(255) NOT NULL,
    category VARCHAR(24) NOT NULL DEFAULT 'MILESTONE',
    condition_json JSON NOT NULL,
    reward_title_code VARCHAR(64) NULL,
    icon_key VARCHAR(64) NOT NULL,
    tone VARCHAR(16) NOT NULL DEFAULT 'violet',
    sort_order INT NOT NULL DEFAULT 0,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_achievement_code (code),
    CONSTRAINT chk_achievement_category CHECK (category IN ('MILESTONE', 'ACTION', 'STREAK', 'ROLE')),
    CONSTRAINT chk_achievement_tone CHECK (tone IN ('green', 'blue', 'amber', 'violet')),
    CONSTRAINT chk_achievement_active CHECK (is_active IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_achievement (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    achievement_code VARCHAR(64) NOT NULL,
    earned_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_achievement (user_id, achievement_code),
    CONSTRAINT fk_user_achievement_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_user_achievement_code FOREIGN KEY (achievement_code) REFERENCES achievement (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 称号：文字 + 图形（LUCIDE 图标 / EMOJI / RIVE 动效），frame_style 关联前端头像框样式
CREATE TABLE title_def (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(255) NOT NULL,
    graphic_type VARCHAR(16) NOT NULL DEFAULT 'LUCIDE',
    graphic_key VARCHAR(64) NOT NULL,
    frame_style VARCHAR(24) NOT NULL DEFAULT 'default',
    sort_order INT NOT NULL DEFAULT 0,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_title_def_code (code),
    CONSTRAINT chk_title_graphic_type CHECK (graphic_type IN ('LUCIDE', 'EMOJI', 'RIVE')),
    CONSTRAINT chk_title_frame_style CHECK (
        frame_style IN ('default', 'emerald', 'green', 'sky', 'ember', 'gold', 'violet', 'rose', 'rainbow')
    ),
    CONSTRAINT chk_title_active CHECK (is_active IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_title (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    title_code VARCHAR(64) NOT NULL,
    equipped TINYINT(1) NOT NULL DEFAULT 0,
    equipped_user_id BIGINT UNSIGNED GENERATED ALWAYS AS (
        CASE WHEN equipped = 1 THEN user_id ELSE NULL END
    ) STORED,
    acquired_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_title (user_id, title_code),
    UNIQUE KEY uk_user_title_single_equipped (equipped_user_id),
    CONSTRAINT fk_user_title_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_user_title_code FOREIGN KEY (title_code) REFERENCES title_def (code),
    CONSTRAINT chk_user_title_equipped CHECK (equipped IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 种子：成就定义（与前端原 badges.ts 的 20 条规则一一对应）
INSERT INTO achievement (code, name, body, category, condition_json, reward_title_code, icon_key, tone, sort_order) VALUES
('FIRST_ACTION', '第一步行动', '迈出第一步，系统才开始有你的真实节奏。', 'ACTION', '{"type":"effective_actions","value":1}', 'START_STEPPER', 'Footprints', 'green', 1),
('THREE_ACTIONS', '三次推进', '一周内完成三次有效推进。', 'ACTION', '{"type":"effective_actions","value":3}', NULL, 'ListChecks', 'green', 2),
('FIVE_ACTIONS', '五点成线', '把零散行动连成更稳定的周节奏。', 'ACTION', '{"type":"effective_actions","value":5}', 'LINKER', 'CheckCircle2', 'green', 3),
('HALF_FULFILLED', '兑现过半', '计划已经不只是写下来。', 'ACTION', '{"type":"fulfillment","value":0.5}', NULL, 'CircleDot', 'blue', 4),
('STABLE_WEEK', '稳定一周', '本周兑现率达到稳定区间。', 'ACTION', '{"type":"fulfillment","value":0.75}', NULL, 'CalendarCheck', 'blue', 5),
('TIGHT_FULFILLMENT', '高度贴合', '计划规模和真实生活匹配得很好。', 'ACTION', '{"type":"fulfillment","value":0.9}', 'FIT_MAKER', 'Target', 'blue', 6),
('GENTLE_RECOVERY', '温和恢复', '中断之后重新回到计划。', 'MILESTONE', '{"type":"recovery_count","value":1}', NULL, 'Leaf', 'amber', 7),
('RECOVERY_SKILLED', '恢复熟练', '你已经在练习不责备地回来。', 'MILESTONE', '{"type":"recovery_count","value":3}', 'REVIVER', 'Repeat2', 'amber', 8),
('SMALL_FLAME', '小火苗', '连续两天留下行动痕迹。', 'STREAK', '{"type":"longest_streak","value":2}', NULL, 'Flame', 'amber', 9),
('WEEK_FOOTPRINT', '一周足迹', '连续一周保持可见行动。', 'STREAK', '{"type":"longest_streak","value":7}', 'WEEK_WALKER', 'CalendarDays', 'amber', 10),
('TEN_EXP', '十点经验', '经验来自行动记录，不代表人格或能力评价。', 'MILESTONE', '{"type":"total_experience","value":10}', NULL, 'Star', 'violet', 11),
('FIFTY_EXP', '五十经验', '积累开始变得可见。', 'MILESTONE', '{"type":"total_experience","value":50}', NULL, 'Sparkles', 'violet', 12),
('HUNDRED_EXP', '百点经验', '长期行动留下了更厚的轨迹。', 'MILESTONE', '{"type":"total_experience","value":100}', 'HUNDRED_SMITH', 'Trophy', 'violet', 13),
('LEARNING_START', '学习起步', '学习角色完成第一次升级。', 'ROLE', '{"type":"role_level","roleCode":"STUDENT","value":2}', 'LEARNER', 'BookOpenCheck', 'green', 14),
('BODY_CARE', '身体照顾', '身体照顾角色完成第一次升级。', 'ROLE', '{"type":"role_level","roleCode":"FITNESS_USER","value":2}', 'BODY_KEEPER', 'Sprout', 'green', 15),
('CAREER_PUSH', '职场推进', '职场角色完成第一次升级。', 'ROLE', '{"type":"role_level","roleCode":"WORKER","value":2}', 'CAREER_PIONEER', 'Rocket', 'blue', 16),
('EMOTION_SUPPORT', '情绪支持', '情绪支持角色完成第一次升级。', 'ROLE', '{"type":"role_level","roleCode":"EMOTIONAL_SUPPORT_USER","value":2}', 'WARM_HEART', 'HeartHandshake', 'violet', 17),
('MULTI_ROLE', '多角色探索', '你开始在多个生活场景里留下行动。', 'ROLE', '{"type":"role_count","minLevel":2,"value":2}', 'MULTI_EXPLORER', 'Compass', 'blue', 18),
('ROLE_ADVANCE', '角色进阶', '多个角色进入更稳定的成长阶段。', 'ROLE', '{"type":"role_count","minLevel":5,"value":2}', 'ADVANCER', 'Mountain', 'violet', 19),
('MAX_ROLE', '满级职业', '至少一个职业等级已满级。', 'ROLE', '{"type":"best_role_level","value":10}', 'PEAK_CLIMBER', 'Medal', 'amber', 20);

-- 种子：称号定义（图形 + 头像框样式）
INSERT INTO title_def (code, name, description, graphic_type, graphic_key, frame_style, sort_order) VALUES
('START_STEPPER', '起步者', '迈出第一步的纪念称号。', 'LUCIDE', 'Footprints', 'emerald', 1),
('LINKER', '连动者', '把零散行动连成线的人。', 'LUCIDE', 'ListChecks', 'green', 2),
('FIT_MAKER', '贴合者', '计划与生活高度贴合的人。', 'LUCIDE', 'Target', 'sky', 3),
('REVIVER', '复苏者', '熟练地回到计划的人。', 'LUCIDE', 'Repeat2', 'emerald', 4),
('WEEK_WALKER', '七日连行者', '连续一周留下可见行动。', 'LUCIDE', 'CalendarDays', 'ember', 5),
('HUNDRED_SMITH', '百炼者', '积累百点经验的长期主义者。', 'LUCIDE', 'Trophy', 'gold', 6),
('LEARNER', '求学人', '学习角色完成首次升级。', 'LUCIDE', 'BookOpenCheck', 'sky', 7),
('BODY_KEEPER', '健行者', '身体照顾角色完成首次升级。', 'LUCIDE', 'Sprout', 'emerald', 8),
('CAREER_PIONEER', '职场人', '职场角色完成首次升级。', 'LUCIDE', 'Rocket', 'violet', 9),
('WARM_HEART', '暖心人', '情绪支持角色完成首次升级。', 'LUCIDE', 'HeartHandshake', 'rose', 10),
('MULTI_EXPLORER', '多面手', '在两个以上场景留下行动。', 'LUCIDE', 'Compass', 'rainbow', 11),
('ADVANCER', '进阶者', '多个角色进入稳定成长阶段。', 'LUCIDE', 'Mountain', 'violet', 12),
('PEAK_CLIMBER', '登顶者', '至少一个职业满级的见证。', 'LUCIDE', 'Medal', 'gold', 13);

ALTER TABLE achievement
    ADD CONSTRAINT fk_achievement_reward_title
        FOREIGN KEY (reward_title_code) REFERENCES title_def (code);
