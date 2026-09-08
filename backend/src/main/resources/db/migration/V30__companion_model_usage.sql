-- Daily model token usage, aggregated per user world / day / call type (decision, turn, summary).
-- Additive counters instead of a per-call log: this project only needs "how much did today cost"
-- and "which call type is priciest", and counters never grow unbounded.
CREATE TABLE town_companion_model_usage (
    user_id BIGINT UNSIGNED NOT NULL,
    usage_date DATE NOT NULL,
    call_type VARCHAR(16) NOT NULL,
    call_count INT UNSIGNED NOT NULL DEFAULT 0,
    input_tokens BIGINT UNSIGNED NOT NULL DEFAULT 0,
    output_tokens BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, usage_date, call_type),
    CONSTRAINT fk_companion_model_usage_user FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
