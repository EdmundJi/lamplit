CREATE TABLE friend_message (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    sender_user_id BIGINT UNSIGNED NOT NULL,
    receiver_user_id BIGINT UNSIGNED NOT NULL,
    body VARCHAR(1000) NOT NULL,
    read_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_friend_message_public_id (public_id),
    KEY idx_friend_message_pair_time (sender_user_id, receiver_user_id, created_at),
    KEY idx_friend_message_receiver_unread (receiver_user_id, read_at),
    CONSTRAINT fk_friend_message_sender FOREIGN KEY (sender_user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_friend_message_receiver FOREIGN KEY (receiver_user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_friend_message_body CHECK (CHAR_LENGTH(body) BETWEEN 1 AND 1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
