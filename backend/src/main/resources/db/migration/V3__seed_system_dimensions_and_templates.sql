INSERT INTO growth_dimension (public_id, owner_user_id, code, name, description, is_system)
VALUES
    ('00000000000000000000000001', NULL, 'KNOWLEDGE', '知识', '学习、理解与技能积累', 1),
    ('00000000000000000000000002', NULL, 'HEALTH', '健康', '运动、睡眠与身体照顾', 1),
    ('00000000000000000000000003', NULL, 'CAREER', '职场', '工作能力与职业发展', 1),
    ('00000000000000000000000004', NULL, 'RELATIONSHIP', '关系', '沟通、支持与边界', 1),
    ('00000000000000000000000005', NULL, 'WELLBEING', '心境', '日常觉察与情绪照顾', 1);

INSERT INTO task_template (
    public_id, scene, name, description, estimated_minutes, difficulty,
    dimension_code, dimension_weight, review_status, version, published_at
)
VALUES
    ('00000000000000000000000101', 'STUDY', '整理今日重点', '写下三个最重要的知识点。', 10, 1, 'KNOWLEDGE', 5, 'PUBLISHED', 1, UTC_TIMESTAMP(3)),
    ('00000000000000000000000102', 'STUDY', '专注阅读', '选择一小节内容，关闭干扰后阅读。', 25, 2, 'KNOWLEDGE', 8, 'PUBLISHED', 1, UTC_TIMESTAMP(3)),
    ('00000000000000000000000103', 'STUDY', '主动回忆', '不看资料复述今天学习的内容。', 15, 2, 'KNOWLEDGE', 7, 'PUBLISHED', 1, UTC_TIMESTAMP(3)),
    ('00000000000000000000000104', 'STUDY', '错题复盘', '分析一道错题并记录原因。', 20, 2, 'KNOWLEDGE', 8, 'PUBLISHED', 1, UTC_TIMESTAMP(3)),
    ('00000000000000000000000105', 'STUDY', '制定明日学习清单', '安排三项可完成的小任务。', 10, 1, 'KNOWLEDGE', 4, 'PUBLISHED', 1, UTC_TIMESTAMP(3)),
    ('00000000000000000000000201', 'FITNESS', '轻松步行', '以能自然交谈的节奏步行。', 20, 1, 'HEALTH', 5, 'PUBLISHED', 1, UTC_TIMESTAMP(3)),
    ('00000000000000000000000202', 'FITNESS', '基础拉伸', '完成温和的全身拉伸，不追求疼痛。', 10, 1, 'HEALTH', 4, 'PUBLISHED', 1, UTC_TIMESTAMP(3)),
    ('00000000000000000000000203', 'FITNESS', '自重基础训练', '按当前能力完成短组基础动作。', 20, 2, 'HEALTH', 8, 'PUBLISHED', 1, UTC_TIMESTAMP(3)),
    ('00000000000000000000000204', 'FITNESS', '准备饮水', '为今天准备充足饮水并设置提醒。', 5, 1, 'HEALTH', 2, 'PUBLISHED', 1, UTC_TIMESTAMP(3)),
    ('00000000000000000000000205', 'FITNESS', '记录睡眠', '记录昨晚入睡和起床时间。', 5, 1, 'HEALTH', 3, 'PUBLISHED', 1, UTC_TIMESTAMP(3)),
    ('00000000000000000000000301', 'CAREER', '明确首要工作', '选出今天最重要且可完成的一项工作。', 10, 1, 'CAREER', 4, 'PUBLISHED', 1, UTC_TIMESTAMP(3)),
    ('00000000000000000000000302', 'CAREER', '专注工作块', '处理一个明确的小目标并关闭通知。', 30, 2, 'CAREER', 9, 'PUBLISHED', 1, UTC_TIMESTAMP(3)),
    ('00000000000000000000000303', 'CAREER', '会议准备', '整理议题、目标和需要确认的问题。', 15, 1, 'CAREER', 5, 'PUBLISHED', 1, UTC_TIMESTAMP(3)),
    ('00000000000000000000000304', 'CAREER', '工作复盘', '记录完成事项、阻碍和下一步。', 10, 1, 'CAREER', 4, 'PUBLISHED', 1, UTC_TIMESTAMP(3)),
    ('00000000000000000000000305', 'CAREER', '学习一个工作技巧', '选择一个可立即应用的小技巧练习。', 20, 2, 'CAREER', 6, 'PUBLISHED', 1, UTC_TIMESTAMP(3)),
    ('00000000000000000000000401', 'EMOTIONAL_SUPPORT', '一分钟呼吸停顿', '把注意力带回呼吸，不评价当下感受。', 5, 1, 'WELLBEING', 2, 'PUBLISHED', 1, UTC_TIMESTAMP(3)),
    ('00000000000000000000000402', 'EMOTIONAL_SUPPORT', '写下此刻感受', '用几个词描述感受和身体反应。', 10, 1, 'WELLBEING', 4, 'PUBLISHED', 1, UTC_TIMESTAMP(3)),
    ('00000000000000000000000403', 'EMOTIONAL_SUPPORT', '联系可信任的人', '向一位可信任的人发送简短问候。', 10, 1, 'RELATIONSHIP', 4, 'PUBLISHED', 1, UTC_TIMESTAMP(3)),
    ('00000000000000000000000404', 'EMOTIONAL_SUPPORT', '温和整理环境', '只整理眼前一个小区域。', 10, 1, 'WELLBEING', 3, 'PUBLISHED', 1, UTC_TIMESTAMP(3)),
    ('00000000000000000000000405', 'EMOTIONAL_SUPPORT', '安排短暂休息', '离开屏幕并进行不带目标的短休息。', 10, 1, 'WELLBEING', 3, 'PUBLISHED', 1, UTC_TIMESTAMP(3));
