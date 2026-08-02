ALTER TABLE task_template
    ADD COLUMN planned_local_time TIME NULL AFTER difficulty,
    ADD COLUMN rrule VARCHAR(255) NULL AFTER planned_local_time;

UPDATE task_template
SET planned_local_time = '19:00:00',
    rrule = 'FREQ=WEEKLY;BYDAY=MO,WE,FR',
    review_status = 'RETIRED'
WHERE review_status = 'PUBLISHED';

ALTER TABLE task_template
    MODIFY COLUMN planned_local_time TIME NOT NULL;

ALTER TABLE user_task
    ADD COLUMN role_code VARCHAR(32) NULL DEFAULT 'STUDENT' AFTER source_template_id;

UPDATE user_task
SET role_code = CASE
    WHEN JSON_CONTAINS_PATH(dimension_weights, 'one', '$.KNOWLEDGE') THEN 'STUDENT'
    WHEN JSON_CONTAINS_PATH(dimension_weights, 'one', '$.HEALTH') THEN 'FITNESS_USER'
    WHEN JSON_CONTAINS_PATH(dimension_weights, 'one', '$.CAREER') THEN 'WORKER'
    ELSE 'EMOTIONAL_SUPPORT_USER'
END;

ALTER TABLE user_task
    MODIFY COLUMN role_code VARCHAR(32) NOT NULL DEFAULT 'STUDENT',
    ADD CONSTRAINT chk_user_task_role CHECK (role_code IN ('STUDENT', 'FITNESS_USER', 'WORKER', 'EMOTIONAL_SUPPORT_USER'));

ALTER TABLE task_event
    ADD COLUMN role_code_snapshot VARCHAR(32) NULL AFTER difficulty_snapshot,
    ADD COLUMN role_experience_delta INT NOT NULL DEFAULT 0 AFTER experience_delta;

UPDATE task_event e
JOIN task_schedule s ON s.id = e.schedule_id
JOIN user_task t ON t.id = s.task_id
SET e.role_code_snapshot = t.role_code;

ALTER TABLE task_event
    MODIFY COLUMN role_code_snapshot VARCHAR(32) NOT NULL DEFAULT 'STUDENT',
    ADD CONSTRAINT chk_task_event_role CHECK (role_code_snapshot IN ('STUDENT', 'FITNESS_USER', 'WORKER', 'EMOTIONAL_SUPPORT_USER'));

CREATE TABLE user_role_progress (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    experience TINYINT UNSIGNED NOT NULL DEFAULT 0,
    level TINYINT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role_progress (user_id, role_code),
    CONSTRAINT fk_user_role_progress_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_user_role_progress_role CHECK (role_code IN ('STUDENT', 'FITNESS_USER', 'WORKER', 'EMOTIONAL_SUPPORT_USER')),
    CONSTRAINT chk_user_role_progress_experience CHECK (experience BETWEEN 0 AND 99),
    CONSTRAINT chk_user_role_progress_level CHECK (level BETWEEN 1 AND 10)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_preset_quota (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    local_date DATE NOT NULL,
    refresh_count TINYINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_preset_quota_user_date (user_id, local_date),
    CONSTRAINT fk_task_preset_quota_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_task_preset_quota_count CHECK (refresh_count BETWEEN 0 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_preset_draw (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    local_date DATE NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    template_public_ids JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_preset_draw_user_date_role (user_id, local_date, role_code),
    CONSTRAINT fk_task_preset_draw_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_task_preset_draw_role CHECK (role_code IN ('STUDENT', 'FITNESS_USER', 'WORKER', 'EMOTIONAL_SUPPORT_USER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO user_role_progress (user_id, role_code)
SELECT u.id, roles.role_code
FROM sys_user u
CROSS JOIN (
    SELECT 'STUDENT' role_code
    UNION ALL SELECT 'FITNESS_USER'
    UNION ALL SELECT 'WORKER'
    UNION ALL SELECT 'EMOTIONAL_SUPPORT_USER'
) roles;

CREATE TEMPORARY TABLE seed_task_base (
    scene VARCHAR(32) NOT NULL,
    role_number TINYINT UNSIGNED NOT NULL,
    base_number TINYINT UNSIGNED NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(700) NOT NULL,
    estimated_minutes SMALLINT UNSIGNED NOT NULL,
    difficulty TINYINT UNSIGNED NOT NULL,
    dimension_code VARCHAR(40) NOT NULL,
    rrule VARCHAR(255) NOT NULL
);

INSERT INTO seed_task_base VALUES
('STUDY', 1, 1, '预习一节课程', '浏览标题、目录和关键概念，写下一个想弄明白的问题。', 15, 1, 'KNOWLEDGE', 'FREQ=WEEKLY;BYDAY=MO,WE,FR'),
('STUDY', 1, 2, '精读一段材料', '选择边界清晰的一小段，标出核心观点和证据。', 25, 2, 'KNOWLEDGE', 'FREQ=WEEKLY;BYDAY=TU,TH'),
('STUDY', 1, 3, '整理课堂笔记', '把零散记录整理为标题、要点和待确认问题。', 20, 2, 'KNOWLEDGE', 'FREQ=WEEKLY;BYDAY=MO,WE,FR'),
('STUDY', 1, 4, '主动回忆知识点', '合上资料，写出今天还能记住的三个关键点。', 15, 2, 'KNOWLEDGE', 'FREQ=DAILY'),
('STUDY', 1, 5, '完成一组练习题', '完成一组范围明确的练习，并标记不确定的题目。', 30, 2, 'KNOWLEDGE', 'FREQ=WEEKLY;BYDAY=TU,TH,SA'),
('STUDY', 1, 6, '复盘一道错题', '记录错误原因、正确路径和下次识别线索。', 20, 2, 'KNOWLEDGE', 'FREQ=WEEKLY;BYDAY=WE,SA'),
('STUDY', 1, 7, '记忆十个词汇', '用例句或联想记忆十个词汇，并做一次遮挡回忆。', 15, 1, 'KNOWLEDGE', 'FREQ=DAILY'),
('STUDY', 1, 8, '练习一项技能', '选择一个可重复的小动作，完成一轮有反馈的练习。', 30, 3, 'KNOWLEDGE', 'FREQ=WEEKLY;BYDAY=MO,WE,FR'),
('STUDY', 1, 9, '输出学习总结', '用自己的话写一段总结，说明学到了什么和仍不清楚什么。', 20, 2, 'KNOWLEDGE', 'FREQ=WEEKLY;BYDAY=FR'),
('STUDY', 1, 10, '安排下一次学习', '确定下一次学习的主题、材料、开始时间和最小完成标准。', 10, 1, 'KNOWLEDGE', 'FREQ=WEEKLY;BYDAY=SU'),

('FITNESS', 2, 1, '完成温和热身', '活动主要关节并逐步提高身体温度，不追求疼痛或极限。', 10, 1, 'HEALTH', 'FREQ=WEEKLY;BYDAY=MO,WE,FR'),
('FITNESS', 2, 2, '进行轻松步行', '以可以自然交谈的节奏步行，按当前状态随时缩短。', 20, 1, 'HEALTH', 'FREQ=DAILY'),
('FITNESS', 2, 3, '练习基础力量', '选择熟悉的基础动作，保持稳定姿势并预留余力。', 25, 2, 'HEALTH', 'FREQ=WEEKLY;BYDAY=MO,TH'),
('FITNESS', 2, 4, '进行核心训练', '完成短组核心稳定练习，动作质量优先于次数。', 15, 2, 'HEALTH', 'FREQ=WEEKLY;BYDAY=TU,FR'),
('FITNESS', 2, 5, '完成全身拉伸', '温和拉伸紧张部位，保持顺畅呼吸，不强压关节。', 10, 1, 'HEALTH', 'FREQ=DAILY'),
('FITNESS', 2, 6, '准备今日饮水', '准备容易取用的饮水，并根据口渴和活动情况补充。', 5, 1, 'HEALTH', 'FREQ=DAILY'),
('FITNESS', 2, 7, '记录昨夜睡眠', '记录入睡、起床时间和主观精神状态，不做医学判断。', 5, 1, 'HEALTH', 'FREQ=DAILY'),
('FITNESS', 2, 8, '记录一次训练', '写下动作、时长、主观强度和身体感受，保留调整空间。', 10, 1, 'HEALTH', 'FREQ=WEEKLY;BYDAY=MO,WE,FR'),
('FITNESS', 2, 9, '练习身体平衡', '在安全环境中完成低风险平衡练习，必要时借助支撑。', 10, 2, 'HEALTH', 'FREQ=WEEKLY;BYDAY=TU,TH'),
('FITNESS', 2, 10, '安排一次户外活动', '选择安全、可控的户外活动，并提前确认时间和装备。', 30, 2, 'HEALTH', 'FREQ=WEEKLY;BYDAY=SA'),

('CAREER', 3, 1, '明确今日首要工作', '选出今天最重要且可以完成的一项工作，写下完成标准。', 10, 1, 'CAREER', 'FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR'),
('CAREER', 3, 2, '完成一个专注工作块', '关闭非必要通知，只推进一个边界清晰的工作结果。', 30, 2, 'CAREER', 'FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR'),
('CAREER', 3, 3, '清理一组待办', '选择三项短待办，完成、委派或明确下一步。', 20, 2, 'CAREER', 'FREQ=WEEKLY;BYDAY=TU,TH'),
('CAREER', 3, 4, '准备一次会议', '整理会议目标、议题、所需材料和待确认问题。', 15, 1, 'CAREER', 'FREQ=WEEKLY;BYDAY=MO,WE'),
('CAREER', 3, 5, '完成一次沟通确认', '用清晰文字确认目标、负责人、截止时间和下一步。', 10, 1, 'CAREER', 'FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR'),
('CAREER', 3, 6, '进行工作复盘', '记录完成事项、主要阻碍、有效做法和下一步调整。', 15, 2, 'CAREER', 'FREQ=WEEKLY;BYDAY=FR'),
('CAREER', 3, 7, '学习一个工作技巧', '选择一个可立即应用的小技巧，阅读并完成一次练习。', 25, 2, 'CAREER', 'FREQ=WEEKLY;BYDAY=WE'),
('CAREER', 3, 8, '整理当前工作区', '只整理当前任务需要的文件、桌面或数字工作区。', 10, 1, 'CAREER', 'FREQ=WEEKLY;BYDAY=MO,FR'),
('CAREER', 3, 9, '规划项目下一步', '把当前项目拆成一个明确、可交付、可安排的下一步。', 20, 2, 'CAREER', 'FREQ=WEEKLY;BYDAY=TH'),
('CAREER', 3, 10, '设置今日下班边界', '写下今天停止工作的时间和未完成事项的承接方式。', 5, 1, 'CAREER', 'FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR'),

('EMOTIONAL_SUPPORT', 4, 1, '进行呼吸停顿', '把注意力带回自然呼吸，允许感受存在，不评价好坏。', 5, 1, 'WELLBEING', 'FREQ=DAILY'),
('EMOTIONAL_SUPPORT', 4, 2, '写下此刻感受', '用几个词记录情绪、身体反应和当下需要，不急于解释。', 10, 1, 'WELLBEING', 'FREQ=DAILY'),
('EMOTIONAL_SUPPORT', 4, 3, '完成温和身体扫描', '从头到脚觉察身体感觉，遇到不适时停止或缩短。', 10, 1, 'WELLBEING', 'FREQ=DAILY'),
('EMOTIONAL_SUPPORT', 4, 4, '整理眼前小区域', '只整理触手可及的一小块空间，完成后就停下。', 10, 1, 'WELLBEING', 'FREQ=WEEKLY;BYDAY=MO,WE,FR'),
('EMOTIONAL_SUPPORT', 4, 5, '安排一次离屏休息', '离开屏幕，选择不带绩效目标的安静休息。', 10, 1, 'WELLBEING', 'FREQ=DAILY'),
('EMOTIONAL_SUPPORT', 4, 6, '联系一位可信任的人', '发送简短问候或说明希望被倾听，不要求对方立即解决问题。', 10, 1, 'RELATIONSHIP', 'FREQ=WEEKLY;BYDAY=TU,SA'),
('EMOTIONAL_SUPPORT', 4, 7, '写下三件可控事项', '区分可控与不可控，把注意力放到一个可执行的小动作。', 10, 2, 'WELLBEING', 'FREQ=WEEKLY;BYDAY=MO,TH'),
('EMOTIONAL_SUPPORT', 4, 8, '记录一件值得感谢的事', '记录一个具体的人、事件或微小片刻，不强迫积极。', 5, 1, 'WELLBEING', 'FREQ=DAILY'),
('EMOTIONAL_SUPPORT', 4, 9, '进行一次温和散步', '在安全环境中缓慢走动，把注意力放在周围可见事物。', 15, 1, 'WELLBEING', 'FREQ=WEEKLY;BYDAY=TU,TH,SA'),
('EMOTIONAL_SUPPORT', 4, 10, '完成睡前卸载', '把挂念事项写下来，标注明天再处理的第一步。', 10, 1, 'WELLBEING', 'FREQ=DAILY');

CREATE TEMPORARY TABLE seed_task_variant (
    variant_number TINYINT UNSIGNED NOT NULL,
    name_prefix VARCHAR(30) NOT NULL,
    guidance VARCHAR(220) NOT NULL,
    minute_delta SMALLINT NOT NULL,
    difficulty_delta SMALLINT NOT NULL,
    planned_local_time TIME NOT NULL
);

INSERT INTO seed_task_variant VALUES
(1, '轻量：', '只完成最小版本，不追求一次到位', -5, -1, '07:30:00'),
(2, '专注：', '关闭一项干扰，在约定时间内只做这一件事', 0, 0, '12:30:00'),
(3, '稳步：', '按舒适节奏推进，中途可以短暂停顿', 5, 0, '18:30:00'),
(4, '进阶：', '仅在状态允许时增加一点挑战，并保留余力', 10, 1, '20:00:00'),
(5, '复盘：', '完成后用一句话记录结果和下次调整', 0, 0, '21:00:00');

INSERT INTO task_template (
    public_id, scene, name, description, estimated_minutes, difficulty,
    planned_local_time, rrule, dimension_code, dimension_weight,
    review_status, version, published_at
)
SELECT
    CONCAT('6', LPAD(base.role_number * 1000 + base.base_number * 10 + variant.variant_number, 25, '0')),
    base.scene,
    CONCAT(variant.name_prefix, base.name),
    CONCAT(variant.guidance, '；', base.description),
    LEAST(60, GREATEST(5, base.estimated_minutes + variant.minute_delta)),
    LEAST(3, GREATEST(1, base.difficulty + variant.difficulty_delta)),
    variant.planned_local_time,
    base.rrule,
    base.dimension_code,
    10,
    'PUBLISHED',
    2,
    UTC_TIMESTAMP(3)
FROM seed_task_base base
CROSS JOIN seed_task_variant variant;

DROP TEMPORARY TABLE seed_task_variant;
DROP TEMPORARY TABLE seed_task_base;
