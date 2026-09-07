-- M7-1：节律（起居时刻、常去地点与停留时长）+ 护栏 A 的每日预算持久化。

-- 1. town_npc.rhythm —— 由五维度兴趣 + 性格（share_drive/curiosity）推出的"平常的一天"，
-- 生产路径见 TownNpcRhythm.defaultFor（TownNpcProvisioner 建号时算好写入）。
-- 先建成 NULL，把已有的 18 行按当时的算法结果回填，再收紧成 NOT NULL——这是数据迁移里
-- 标准的"补齐历史数据"做法，回填的 18 条 JSON 是从 TownNpcRhythm.defaultFor 对当时
-- TownNpcCatalog 的输出里原样导出的，不是另开的一份口径。往后任何新用户的新行都由
-- TownNpcProvisioner 在插入时直接算好写入，不再依赖这里的 CASE。
ALTER TABLE town_npc ADD COLUMN rhythm JSON NULL AFTER quirks;

UPDATE town_npc SET rhythm = CASE npc_code
    WHEN 'GUIDE' THEN CAST('{"wakeMinute":312,"sleepMinute":1325,"errands":[{"place":"gym","startMinute":619,"durationMinutes":60},{"place":"plaza","startMinute":956,"durationMinutes":60}]}' AS JSON)
    WHEN 'POSTMAN' THEN CAST('{"wakeMinute":372,"sleepMinute":1338,"errands":[{"place":"cafe","startMinute":642,"durationMinutes":105},{"place":"park","startMinute":986,"durationMinutes":60}]}' AS JSON)
    WHEN 'KE_YUN' THEN CAST('{"wakeMinute":318,"sleepMinute":1365,"errands":[{"place":"academy","startMinute":504,"durationMinutes":150},{"place":"gym","startMinute":818,"durationMinutes":45},{"place":"plaza","startMinute":1079,"durationMinutes":45}]}' AS JSON)
    WHEN 'LU_XIA' THEN CAST('{"wakeMinute":360,"sleepMinute":1397,"errands":[{"place":"gym","startMinute":492,"durationMinutes":150},{"place":"plaza","startMinute":752,"durationMinutes":45},{"place":"cafe","startMinute":959,"durationMinutes":45},{"place":"park","startMinute":1166,"durationMinutes":45}]}' AS JSON)
    WHEN 'SHEN_MU' THEN CAST('{"wakeMinute":384,"sleepMinute":1334,"errands":[{"place":"plaza","startMinute":625,"durationMinutes":150},{"place":"gym","startMinute":994,"durationMinutes":45}]}' AS JSON)
    WHEN 'WEN_QING' THEN CAST('{"wakeMinute":312,"sleepMinute":1401,"errands":[{"place":"cafe","startMinute":454,"durationMinutes":150},{"place":"academy","startMinute":724,"durationMinutes":45},{"place":"gym","startMinute":941,"durationMinutes":45},{"place":"plaza","startMinute":1158,"durationMinutes":45}]}' AS JSON)
    WHEN 'AN_HE' THEN CAST('{"wakeMinute":324,"sleepMinute":1338,"errands":[{"place":"park","startMinute":587,"durationMinutes":150},{"place":"academy","startMinute":978,"durationMinutes":45}]}' AS JSON)
    WHEN 'JI_MAI' THEN CAST('{"wakeMinute":348,"sleepMinute":1374,"errands":[{"place":"gym","startMinute":574,"durationMinutes":60},{"place":"plaza","startMinute":830,"durationMinutes":60},{"place":"cafe","startMinute":1086,"durationMinutes":60}]}' AS JSON)
    WHEN 'TOWNIE_01' THEN CAST('{"wakeMinute":378,"sleepMinute":1351,"errands":[{"place":"gym","startMinute":576,"durationMinutes":90},{"place":"cafe","startMinute":827,"durationMinutes":75},{"place":"plaza","startMinute":1085,"durationMinutes":45}]}' AS JSON)
    WHEN 'TOWNIE_02' THEN CAST('{"wakeMinute":384,"sleepMinute":1356,"errands":[{"place":"plaza","startMinute":590,"durationMinutes":75},{"place":"cafe","startMinute":840,"durationMinutes":60},{"place":"park","startMinute":1083,"durationMinutes":60}]}' AS JSON)
    WHEN 'TOWNIE_03' THEN CAST('{"wakeMinute":390,"sleepMinute":1338,"errands":[{"place":"plaza","startMinute":646,"durationMinutes":120},{"place":"park","startMinute":992,"durationMinutes":60}]}' AS JSON)
    WHEN 'TOWNIE_04' THEN CAST('{"wakeMinute":372,"sleepMinute":1361,"errands":[{"place":"academy","startMinute":582,"durationMinutes":75},{"place":"gym","startMinute":829,"durationMinutes":75},{"place":"park","startMinute":1076,"durationMinutes":75}]}' AS JSON)
    WHEN 'TOWNIE_05' THEN CAST('{"wakeMinute":354,"sleepMinute":1370,"errands":[{"place":"cafe","startMinute":563,"durationMinutes":90},{"place":"gym","startMinute":832,"durationMinutes":60},{"place":"plaza","startMinute":1086,"durationMinutes":60}]}' AS JSON)
    WHEN 'TOWNIE_06' THEN CAST('{"wakeMinute":378,"sleepMinute":1347,"errands":[{"place":"gym","startMinute":568,"durationMinutes":105},{"place":"park","startMinute":832,"durationMinutes":60},{"place":"plaza","startMinute":1082,"durationMinutes":45}]}' AS JSON)
    WHEN 'TOWNIE_07' THEN CAST('{"wakeMinute":384,"sleepMinute":1347,"errands":[{"place":"academy","startMinute":579,"durationMinutes":90},{"place":"cafe","startMinute":834,"durationMinutes":60},{"place":"park","startMinute":1074,"durationMinutes":60}]}' AS JSON)
    WHEN 'TOWNIE_08' THEN CAST('{"wakeMinute":396,"sleepMinute":1343,"errands":[{"place":"park","startMinute":587,"durationMinutes":90},{"place":"plaza","startMinute":823,"durationMinutes":90},{"place":"gym","startMinute":1074,"durationMinutes":60}]}' AS JSON)
    WHEN 'TOWNIE_09' THEN CAST('{"wakeMinute":366,"sleepMinute":1356,"errands":[{"place":"cafe","startMinute":576,"durationMinutes":75},{"place":"park","startMinute":823,"durationMinutes":75},{"place":"academy","startMinute":1077,"durationMinutes":60}]}' AS JSON)
    WHEN 'TOWNIE_10' THEN CAST('{"wakeMinute":384,"sleepMinute":1347,"errands":[{"place":"park","startMinute":564,"durationMinutes":120},{"place":"plaza","startMinute":842,"durationMinutes":45},{"place":"cafe","startMinute":1082,"durationMinutes":45}]}' AS JSON)
    -- 兜底：不在固定 18 人名册里的行不该存在，但迁移不该因为一条脏数据整体失败，
    -- 给一份"没有常态行程、只在家"的最小合法值。
    ELSE CAST('{"wakeMinute":420,"sleepMinute":1320,"errands":[]}' AS JSON)
END
WHERE rhythm IS NULL;

ALTER TABLE town_npc MODIFY COLUMN rhythm JSON NOT NULL;

-- 2. 护栏 A（plan §2.4）的每日预算持久化。之前 initiativeBudget.used 只在前端内存里算，
-- 刷新即归零；现在按 (user_id, local_date) 落一行，谁调 POST /town/initiative/consume
-- 谁的 used +1，顶到上限（后端常量 TownSocietyService.DAILY_INITIATIVE_LIMIT=3）不再往上涨。
CREATE TABLE town_initiative_budget (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    local_date DATE NOT NULL,
    used INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_town_initiative_budget (user_id, local_date),
    CONSTRAINT fk_town_initiative_budget_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_town_initiative_budget_used CHECK (used >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
