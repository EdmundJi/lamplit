-- 夜间传播 job 的"这一天已经跑过了"标记。
--
-- town_fact 的唯一键让事实本身重跑不翻倍，但流水线里另外三步是会累积的：显著度衰减每调一次
-- 就再衰减一次、传播会在上一轮已经放大的知识集上继续放大 hops、亲密度每跑一次就再涨一轮。
-- 实测同一天重跑几次之后，NPC 之间的 meet_count 到了 40、affinity 顶到 1.0——那不是"这一天
-- 发生的事"，是 job 被触发的次数。所以用一张按 (用户, 当地日期) 唯一的表把整晚锁成一次。
CREATE TABLE town_society_run (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    town_user_id BIGINT UNSIGNED NOT NULL,
    local_date DATE NOT NULL,
    ran_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_town_society_run (town_user_id, local_date),
    CONSTRAINT fk_town_society_run_user FOREIGN KEY (town_user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
