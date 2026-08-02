CREATE TABLE sys_user (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    email VARCHAR(254) NOT NULL,
    email_normalized VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    birth_date DATE NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    role VARCHAR(32) NOT NULL DEFAULT 'USER',
    mfa_secret_encrypted VARBINARY(512) NULL,
    mfa_verified_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_public_id (public_id),
    UNIQUE KEY uk_sys_user_email (email_normalized),
    CONSTRAINT chk_sys_user_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DELETION_PENDING', 'DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE auth_session (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    token_family_id CHAR(26) NOT NULL,
    refresh_token_hash BINARY(32) NOT NULL,
    device_label VARCHAR(120) NULL,
    ip_hash BINARY(32) NULL,
    user_agent_hash BINARY(32) NULL,
    issued_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    rotated_at DATETIME(3) NULL,
    revoked_at DATETIME(3) NULL,
    revoke_reason VARCHAR(40) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_session_public_id (public_id),
    UNIQUE KEY uk_auth_session_refresh_hash (refresh_token_hash),
    KEY idx_auth_session_user_family (user_id, token_family_id),
    CONSTRAINT fk_auth_session_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE consent_record (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    consent_type VARCHAR(32) NOT NULL,
    version VARCHAR(32) NOT NULL,
    granted TINYINT(1) NOT NULL,
    recorded_at DATETIME(3) NOT NULL,
    withdrawn_at DATETIME(3) NULL,
    source_ip_hash BINARY(32) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_consent_user_type_version (user_id, consent_type, version),
    CONSTRAINT fk_consent_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_consent_type CHECK (consent_type IN ('TERMS', 'PRIVACY', 'AI'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_preference (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    scene VARCHAR(32) NULL,
    daily_minutes SMALLINT UNSIGNED NOT NULL DEFAULT 30,
    weekly_frequency TINYINT UNSIGNED NOT NULL DEFAULT 3,
    preferred_difficulty TINYINT UNSIGNED NOT NULL DEFAULT 2,
    timezone VARCHAR(64) NOT NULL,
    quiet_hours_start TIME NULL,
    quiet_hours_end TIME NULL,
    ai_retention_days SMALLINT UNSIGNED NOT NULL DEFAULT 90,
    ai_memory_enabled TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_preference_user (user_id),
    CONSTRAINT fk_user_preference_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_user_preference_frequency CHECK (weekly_frequency BETWEEN 1 AND 7),
    CONSTRAINT chk_user_preference_difficulty CHECK (preferred_difficulty BETWEEN 1 AND 3),
    CONSTRAINT chk_user_preference_retention CHECK (ai_retention_days IN (7, 30, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notification_preference (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    channel VARCHAR(20) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 0,
    max_per_day TINYINT UNSIGNED NOT NULL DEFAULT 2,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_preference (user_id, channel),
    CONSTRAINT fk_notification_preference_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_notification_channel CHECK (channel IN ('IN_APP', 'EMAIL', 'WEB_PUSH')),
    CONSTRAINT chk_notification_max CHECK (max_per_day BETWEEN 0 AND 10)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE growth_dimension (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    owner_user_id BIGINT UNSIGNED NULL,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(400) NULL,
    is_system TINYINT(1) NOT NULL DEFAULT 0,
    archived_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_growth_dimension_public_id (public_id),
    UNIQUE KEY uk_growth_dimension_owner_code (owner_user_id, code),
    CONSTRAINT fk_growth_dimension_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_dimension (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    dimension_id BIGINT UNSIGNED NOT NULL,
    experience INT UNSIGNED NOT NULL DEFAULT 0,
    level SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_dimension (user_id, dimension_id),
    CONSTRAINT fk_user_dimension_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_user_dimension_dimension FOREIGN KEY (dimension_id) REFERENCES growth_dimension (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE growth_goal (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    dimension_id BIGINT UNSIGNED NOT NULL,
    title VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    archived_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_growth_goal_public_id (public_id),
    KEY idx_growth_goal_user_status (user_id, status),
    CONSTRAINT fk_growth_goal_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_growth_goal_dimension FOREIGN KEY (dimension_id) REFERENCES growth_dimension (id),
    CONSTRAINT chk_growth_goal_status CHECK (status IN ('DRAFT', 'ACTIVE', 'COMPLETED', 'ARCHIVED', 'CANCELLED')),
    CONSTRAINT chk_growth_goal_dates CHECK (end_date >= start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE weekly_plan (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    goal_id BIGINT UNSIGNED NOT NULL,
    week_start_date DATE NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    confirmed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_weekly_plan_public_id (public_id),
    UNIQUE KEY uk_weekly_plan_goal_week (goal_id, week_start_date),
    KEY idx_weekly_plan_user_week (user_id, week_start_date),
    CONSTRAINT fk_weekly_plan_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_weekly_plan_goal FOREIGN KEY (goal_id) REFERENCES growth_goal (id),
    CONSTRAINT chk_weekly_plan_status CHECK (status IN ('DRAFT', 'CONFIRMED', 'COMPLETED', 'ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_template (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    scene VARCHAR(32) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NULL,
    estimated_minutes SMALLINT UNSIGNED NOT NULL,
    difficulty TINYINT UNSIGNED NOT NULL,
    dimension_code VARCHAR(40) NOT NULL,
    dimension_weight TINYINT UNSIGNED NOT NULL,
    review_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    version INT UNSIGNED NOT NULL DEFAULT 1,
    published_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_template_public_id (public_id),
    UNIQUE KEY uk_task_template_scene_name_version (scene, name, version),
    CONSTRAINT chk_task_template_minutes CHECK (estimated_minutes BETWEEN 5 AND 60),
    CONSTRAINT chk_task_template_difficulty CHECK (difficulty BETWEEN 1 AND 3),
    CONSTRAINT chk_task_template_weight CHECK (dimension_weight BETWEEN 1 AND 30),
    CONSTRAINT chk_task_template_review CHECK (review_status IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'RETIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_task (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    weekly_plan_id BIGINT UNSIGNED NOT NULL,
    source_template_id BIGINT UNSIGNED NULL,
    title VARCHAR(160) NOT NULL,
    notes TEXT NULL,
    estimated_minutes SMALLINT UNSIGNED NOT NULL,
    difficulty TINYINT UNSIGNED NOT NULL,
    rrule VARCHAR(255) NULL,
    dimension_weights JSON NOT NULL,
    active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_task_public_id (public_id),
    KEY idx_user_task_user_plan (user_id, weekly_plan_id),
    CONSTRAINT fk_user_task_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_user_task_plan FOREIGN KEY (weekly_plan_id) REFERENCES weekly_plan (id),
    CONSTRAINT fk_user_task_template FOREIGN KEY (source_template_id) REFERENCES task_template (id),
    CONSTRAINT chk_user_task_minutes CHECK (estimated_minutes BETWEEN 5 AND 240),
    CONSTRAINT chk_user_task_difficulty CHECK (difficulty BETWEEN 1 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_schedule (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    task_id BIGINT UNSIGNED NOT NULL,
    planned_start_at DATETIME(3) NOT NULL,
    planned_end_at DATETIME(3) NULL,
    local_date DATE NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PLANNED',
    deferred_from_id BIGINT UNSIGNED NULL,
    version INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_schedule_public_id (public_id),
    UNIQUE KEY uk_task_schedule_occurrence (task_id, planned_start_at),
    KEY idx_task_schedule_user_local (user_id, local_date, status),
    CONSTRAINT fk_task_schedule_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_task_schedule_task FOREIGN KEY (task_id) REFERENCES user_task (id),
    CONSTRAINT fk_task_schedule_deferred FOREIGN KEY (deferred_from_id) REFERENCES task_schedule (id),
    CONSTRAINT chk_task_schedule_status CHECK (status IN ('PLANNED', 'IN_PROGRESS', 'DONE', 'PARTIAL', 'DEFERRED', 'SKIPPED', 'EXPIRED', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    schedule_id BIGINT UNSIGNED NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    completion_ratio DECIMAL(5,4) NULL,
    experience_delta INT NOT NULL DEFAULT 0,
    task_title_snapshot VARCHAR(160) NOT NULL,
    estimated_minutes_snapshot SMALLINT UNSIGNED NOT NULL,
    difficulty_snapshot TINYINT UNSIGNED NOT NULL,
    dimension_weights_snapshot JSON NOT NULL,
    note TEXT NULL,
    reverses_event_id BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_event_public_id (public_id),
    KEY idx_task_event_schedule_time (schedule_id, occurred_at),
    KEY idx_task_event_user_time (user_id, occurred_at),
    CONSTRAINT fk_task_event_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_task_event_schedule FOREIGN KEY (schedule_id) REFERENCES task_schedule (id),
    CONSTRAINT fk_task_event_reverses FOREIGN KEY (reverses_event_id) REFERENCES task_event (id),
    CONSTRAINT chk_task_event_type CHECK (event_type IN ('STARTED', 'COMPLETED', 'PARTIAL', 'DEFERRED', 'SKIPPED', 'EXPIRED', 'CANCELLED', 'REVERSED')),
    CONSTRAINT chk_task_event_ratio CHECK (completion_ratio IS NULL OR completion_ratio BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE weekly_review (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    weekly_plan_id BIGINT UNSIGNED NOT NULL,
    facts JSON NOT NULL,
    ai_draft TEXT NULL,
    user_reflection TEXT NULL,
    proposed_adjustments JSON NULL,
    confirmed_adjustments JSON NULL,
    confirmed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_weekly_review_public_id (public_id),
    UNIQUE KEY uk_weekly_review_plan (weekly_plan_id),
    CONSTRAINT fk_weekly_review_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_weekly_review_plan FOREIGN KEY (weekly_plan_id) REFERENCES weekly_plan (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_prompt_version (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    scene VARCHAR(32) NOT NULL,
    version INT UNSIGNED NOT NULL,
    system_prompt TEXT NOT NULL,
    schema_json JSON NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    reviewer_user_id BIGINT UNSIGNED NULL,
    published_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_prompt_public_id (public_id),
    UNIQUE KEY uk_ai_prompt_scene_version (scene, version),
    CONSTRAINT fk_ai_prompt_reviewer FOREIGN KEY (reviewer_user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_ai_prompt_status CHECK (status IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'RETIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE safety_policy_version (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    policy_code VARCHAR(40) NOT NULL,
    version INT UNSIGNED NOT NULL,
    response_text TEXT NOT NULL,
    actions JSON NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    published_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_safety_policy_public_id (public_id),
    UNIQUE KEY uk_safety_policy_code_version (policy_code, version),
    CONSTRAINT chk_safety_policy_status CHECK (status IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'RETIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_session (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    scene VARCHAR(32) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_session_public_id (public_id),
    KEY idx_ai_session_user (user_id, created_at),
    CONSTRAINT fk_ai_session_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_ai_session_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'DELETED', 'EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_message (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    session_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    risk_level VARCHAR(4) NOT NULL DEFAULT 'L0',
    model_name VARCHAR(80) NULL,
    prompt_version_id BIGINT UNSIGNED NULL,
    provider_request_id VARCHAR(120) NULL,
    input_tokens INT UNSIGNED NULL,
    output_tokens INT UNSIGNED NULL,
    latency_ms INT UNSIGNED NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3) NULL,
    deleted_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_message_public_id (public_id),
    KEY idx_ai_message_session_time (session_id, created_at),
    CONSTRAINT fk_ai_message_session FOREIGN KEY (session_id) REFERENCES ai_session (id),
    CONSTRAINT fk_ai_message_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_ai_message_prompt FOREIGN KEY (prompt_version_id) REFERENCES ai_prompt_version (id),
    CONSTRAINT chk_ai_message_role CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM')),
    CONSTRAINT chk_ai_message_risk CHECK (risk_level IN ('L0', 'L1', 'L2', 'L3')),
    CONSTRAINT chk_ai_message_status CHECK (status IN ('PENDING', 'STREAMING', 'COMPLETED', 'FAILED', 'BLOCKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_memory (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    confirmed_at DATETIME(3) NULL,
    expires_at DATETIME(3) NULL,
    deleted_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_memory_public_id (public_id),
    KEY idx_ai_memory_user_status (user_id, status),
    CONSTRAINT fk_ai_memory_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_ai_memory_status CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED', 'DELETED', 'EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_safety_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    user_id BIGINT UNSIGNED NULL,
    session_id BIGINT UNSIGNED NULL,
    scene VARCHAR(32) NOT NULL,
    risk_level VARCHAR(4) NOT NULL,
    direction VARCHAR(8) NOT NULL,
    rule_codes JSON NOT NULL,
    redacted_excerpt VARCHAR(500) NULL,
    policy_version_id BIGINT UNSIGNED NULL,
    review_status VARCHAR(20) NOT NULL DEFAULT 'UNREVIEWED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_safety_event_public_id (public_id),
    KEY idx_ai_safety_risk_time (risk_level, created_at),
    CONSTRAINT fk_ai_safety_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_ai_safety_session FOREIGN KEY (session_id) REFERENCES ai_session (id),
    CONSTRAINT fk_ai_safety_policy FOREIGN KEY (policy_version_id) REFERENCES safety_policy_version (id),
    CONSTRAINT chk_ai_safety_risk CHECK (risk_level IN ('L0', 'L1', 'L2', 'L3')),
    CONSTRAINT chk_ai_safety_direction CHECK (direction IN ('INPUT', 'OUTPUT')),
    CONSTRAINT chk_ai_safety_review CHECK (review_status IN ('UNREVIEWED', 'IN_REVIEW', 'RESOLVED', 'ESCALATED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE data_export_job (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    object_key VARCHAR(512) NULL,
    checksum_sha256 CHAR(64) NULL,
    requested_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    expires_at DATETIME(3) NULL,
    failure_code VARCHAR(80) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_data_export_public_id (public_id),
    KEY idx_data_export_user_time (user_id, requested_at),
    CONSTRAINT fk_data_export_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_data_export_status CHECK (status IN ('PENDING', 'PROCESSING', 'READY', 'EXPIRED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE deletion_request (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'COOLING_OFF',
    requested_at DATETIME(3) NOT NULL,
    process_after DATETIME(3) NOT NULL,
    processing_started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    cancelled_at DATETIME(3) NULL,
    failure_code VARCHAR(80) NULL,
    completion_receipt_hash CHAR(64) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_deletion_request_public_id (public_id),
    KEY idx_deletion_request_user_status (user_id, status),
    CONSTRAINT fk_deletion_request_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_deletion_request_status CHECK (status IN ('COOLING_OFF', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE idempotency_record (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    operation VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    response_status SMALLINT UNSIGNED NULL,
    response_body JSON NULL,
    resource_public_id CHAR(26) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_user_operation_key (user_id, operation, idempotency_key),
    KEY idx_idempotency_expiry (expires_at),
    CONSTRAINT fk_idempotency_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE audit_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    actor_user_id BIGINT UNSIGNED NULL,
    action VARCHAR(80) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_public_id CHAR(26) NULL,
    outcome VARCHAR(20) NOT NULL,
    request_id CHAR(26) NOT NULL,
    metadata JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_audit_log_public_id (public_id),
    KEY idx_audit_actor_time (actor_user_id, created_at),
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_audit_outcome CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
