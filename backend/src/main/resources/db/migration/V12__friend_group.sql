CREATE TABLE friend_group (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    name VARCHAR(80) NOT NULL,
    owner_user_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_friend_group_public_id (public_id),
    KEY idx_friend_group_owner (owner_user_id),
    CONSTRAINT fk_friend_group_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_friend_group_name CHECK (CHAR_LENGTH(name) BETWEEN 1 AND 80)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE friend_group_member (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    group_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    last_read_at DATETIME(3) NULL,
    joined_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_friend_group_member (group_id, user_id),
    KEY idx_friend_group_member_user (user_id),
    CONSTRAINT fk_friend_group_member_group FOREIGN KEY (group_id) REFERENCES friend_group (id) ON DELETE CASCADE,
    CONSTRAINT fk_friend_group_member_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE friend_group_message (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    group_id BIGINT UNSIGNED NOT NULL,
    sender_user_id BIGINT UNSIGNED NOT NULL,
    body VARCHAR(1000) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_friend_group_message_public_id (public_id),
    KEY idx_friend_group_message_group_time (group_id, created_at),
    CONSTRAINT fk_friend_group_message_group FOREIGN KEY (group_id) REFERENCES friend_group (id) ON DELETE CASCADE,
    CONSTRAINT fk_friend_group_message_sender FOREIGN KEY (sender_user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_friend_group_message_body CHECK (CHAR_LENGTH(body) BETWEEN 1 AND 1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
