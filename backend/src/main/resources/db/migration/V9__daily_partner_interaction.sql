CREATE TABLE partner_interaction (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    pet_id BIGINT UNSIGNED NOT NULL,
    interaction_date DATE NOT NULL,
    affection_delta SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    interacted_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_partner_interaction_public_id (public_id),
    UNIQUE KEY uk_partner_interaction_user_date (user_id, interaction_date),
    KEY idx_partner_interaction_pet_time (pet_id, interacted_at),
    CONSTRAINT fk_partner_interaction_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_partner_interaction_pet FOREIGN KEY (pet_id) REFERENCES partner_pet (id),
    CONSTRAINT chk_partner_interaction_affection CHECK (affection_delta BETWEEN 0 AND 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
