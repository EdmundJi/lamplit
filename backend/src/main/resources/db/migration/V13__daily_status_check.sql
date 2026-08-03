CREATE TABLE daily_status_check (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    local_date DATE NOT NULL,
    energy VARCHAR(16) NOT NULL,
    available_minutes SMALLINT UNSIGNED NOT NULL,
    advice VARCHAR(16) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_daily_status_check_public_id (public_id),
    UNIQUE KEY uk_daily_status_check_user_date (user_id, local_date),
    CONSTRAINT fk_daily_status_check_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_daily_status_check_energy CHECK (energy IN ('LOW', 'STEADY', 'OPEN')),
    CONSTRAINT chk_daily_status_check_advice CHECK (advice IN ('SHRINK', 'KEEP', 'LIGHT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
