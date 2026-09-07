-- Standalone checklist tasks share the existing task and event model.
ALTER TABLE user_task MODIFY COLUMN weekly_plan_id BIGINT UNSIGNED NULL;
