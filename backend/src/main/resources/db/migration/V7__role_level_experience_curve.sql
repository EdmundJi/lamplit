ALTER TABLE user_role_progress
    DROP CHECK chk_user_role_progress_experience,
    MODIFY COLUMN experience SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN experience_baseline INT NOT NULL DEFAULT 0 AFTER experience;

-- Preserve each user's existing level and their progress within that level while
-- moving from the original cumulative 0-99 curve to the new per-level curve.
UPDATE user_role_progress
SET experience = CASE level
    WHEN 10 THEN 1150 + GREATEST(0, experience - 72)
    WHEN 9 THEN 705 + GREATEST(0, experience - 60)
    WHEN 8 THEN 430 + GREATEST(0, experience - 49)
    WHEN 7 THEN 260 + GREATEST(0, experience - 39)
    WHEN 6 THEN 155 + GREATEST(0, experience - 30)
    WHEN 5 THEN 90 + GREATEST(0, experience - 22)
    WHEN 4 THEN 50 + GREATEST(0, experience - 15)
    WHEN 3 THEN 25 + GREATEST(0, experience - 9)
    WHEN 2 THEN 10 + GREATEST(0, experience - 4)
    ELSE GREATEST(0, experience)
END;

UPDATE user_role_progress rp
LEFT JOIN (
    SELECT user_id, role_code_snapshot role_code, SUM(role_experience_delta) event_experience
    FROM task_event
    GROUP BY user_id, role_code_snapshot
) totals ON totals.user_id = rp.user_id AND totals.role_code = rp.role_code
SET rp.experience_baseline = rp.experience - COALESCE(totals.event_experience, 0);

ALTER TABLE user_role_progress
    ADD CONSTRAINT chk_user_role_progress_experience CHECK (experience BETWEEN 0 AND 2149);
