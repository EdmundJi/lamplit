ALTER TABLE user_task
    ADD COLUMN planned_local_time TIME NOT NULL DEFAULT '09:00:00' AFTER dimension_weights,
    ADD COLUMN active_from DATE NULL AFTER planned_local_time,
    ADD COLUMN active_until DATE NULL AFTER active_from,
    ADD CONSTRAINT chk_user_task_active_dates CHECK (
        active_until IS NULL OR active_from IS NULL OR active_until >= active_from
    );

ALTER TABLE growth_goal
    DROP CHECK chk_growth_goal_status,
    ADD CONSTRAINT chk_growth_goal_status CHECK (
        status IN ('DRAFT', 'ACTIVE', 'PAUSED', 'COMPLETED', 'ARCHIVED', 'CANCELLED')
    );
