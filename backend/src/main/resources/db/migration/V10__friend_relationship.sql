CREATE TABLE friend_relationship (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    pair_key VARCHAR(45) NOT NULL,
    requester_user_id BIGINT UNSIGNED NOT NULL,
    addressee_user_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_friend_relationship_public_id (public_id),
    UNIQUE KEY uk_friend_pair (pair_key),
    KEY idx_friend_addressee_status (addressee_user_id, status),
    KEY idx_friend_requester_status (requester_user_id, status),
    CONSTRAINT fk_friend_requester FOREIGN KEY (requester_user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_friend_addressee FOREIGN KEY (addressee_user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_friend_relationship_status CHECK (status IN ('PENDING', 'ACCEPTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
