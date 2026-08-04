ALTER TABLE user_preference
    ADD COLUMN solo_growth TINYINT(1) NOT NULL DEFAULT 0 AFTER ai_memory_enabled;
