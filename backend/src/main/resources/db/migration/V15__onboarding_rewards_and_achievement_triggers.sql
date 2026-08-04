ALTER TABLE achievement
    ADD COLUMN trigger_text VARCHAR(160) NOT NULL DEFAULT '' AFTER body;

UPDATE achievement
SET trigger_text = CASE code
    WHEN 'FIRST_ACTION' THEN '本周有效行动 >= 1'
    WHEN 'THREE_ACTIONS' THEN '本周有效行动 >= 3'
    WHEN 'FIVE_ACTIONS' THEN '本周有效行动 >= 5'
    WHEN 'HALF_FULFILLED' THEN '本周兑现率 >= 50%'
    WHEN 'STABLE_WEEK' THEN '本周兑现率 >= 75%'
    WHEN 'TIGHT_FULFILLMENT' THEN '本周兑现率 >= 90%'
    WHEN 'GENTLE_RECOVERY' THEN '恢复次数 >= 1'
    WHEN 'RECOVERY_SKILLED' THEN '恢复次数 >= 3'
    WHEN 'SMALL_FLAME' THEN '连续行动天数 >= 2'
    WHEN 'WEEK_FOOTPRINT' THEN '连续行动天数 >= 7'
    WHEN 'TEN_EXP' THEN '累计经验 >= 10'
    WHEN 'FIFTY_EXP' THEN '累计经验 >= 50'
    WHEN 'HUNDRED_EXP' THEN '累计经验 >= 100'
    WHEN 'LEARNING_START' THEN '学生等级 >= 2'
    WHEN 'BODY_CARE' THEN '健身用户等级 >= 2'
    WHEN 'CAREER_PUSH' THEN '打工人等级 >= 2'
    WHEN 'EMOTION_SUPPORT' THEN '情绪支持等级 >= 2'
    WHEN 'MULTI_ROLE' THEN '至少 2 个角色达到 LV.2'
    WHEN 'ROLE_ADVANCE' THEN '至少 2 个角色达到 LV.5'
    WHEN 'MAX_ROLE' THEN '任一角色达到 LV.10'
    ELSE '完成对应成长条件'
END;

INSERT INTO title_def (
    code, name, description, graphic_type, graphic_key, frame_style, sort_order
) VALUES (
    'NEWCOMER_PATH', '成长之路', '完成起步设置，留下第一份成长计划。',
    'LUCIDE', 'Route', 'emerald', 0
);

ALTER TABLE user_preference
    ADD COLUMN onboarding_completed_at DATETIME(3) NULL AFTER ai_memory_enabled;
