-- One row per user: the last position the server accepted, so a resident's spot survives a
-- refresh instead of being recomputed (and teleporting) from the schedule every time.
CREATE TABLE town_presence (
    user_id BIGINT UNSIGNED NOT NULL,
    x DOUBLE NOT NULL,
    y DOUBLE NOT NULL,
    facing VARCHAR(8) NOT NULL,
    scene VARCHAR(32) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT chk_town_presence_facing CHECK (facing IN ('up', 'down', 'left', 'right')),
    CONSTRAINT fk_town_presence_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
