CREATE TABLE town_visit_profile (
 user_id BIGINT UNSIGNED PRIMARY KEY,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 style VARCHAR(16) NOT NULL DEFAULT 'original',
 updated_at DATETIME(3) NOT NULL,
 CONSTRAINT fk_town_visit_profile_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE town_visit_memento (
 user_id BIGINT UNSIGNED NOT NULL,
 achievement_code VARCHAR(64) NOT NULL,
 PRIMARY KEY (user_id, achievement_code),
 CONSTRAINT fk_town_visit_memento_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE town_visit_postcard (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 public_id CHAR(26) NOT NULL UNIQUE,
 owner_user_id BIGINT UNSIGNED NOT NULL,
 sender_user_id BIGINT UNSIGNED NOT NULL,
 request_key VARCHAR(64) NOT NULL,
 body VARCHAR(300) NOT NULL,
 created_at DATETIME(3) NOT NULL,
 owner_deleted BOOLEAN NOT NULL DEFAULT FALSE,
 sender_deleted BOOLEAN NOT NULL DEFAULT FALSE,
 UNIQUE KEY uq_visit_postcard_request (sender_user_id, request_key),
 INDEX idx_visit_postcard_owner (owner_user_id, created_at),
 CONSTRAINT fk_visit_postcard_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user(id),
 CONSTRAINT fk_visit_postcard_sender FOREIGN KEY (sender_user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
