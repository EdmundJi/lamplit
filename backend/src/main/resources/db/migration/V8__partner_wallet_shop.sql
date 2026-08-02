CREATE TABLE user_wallet (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    coin_balance INT UNSIGNED NOT NULL DEFAULT 0,
    lifetime_coins INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_wallet_user (user_id),
    CONSTRAINT fk_user_wallet_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_user_wallet_balance CHECK (coin_balance <= 999999),
    CONSTRAINT chk_user_wallet_lifetime CHECK (lifetime_coins <= 999999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE partner_pet (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    species_code VARCHAR(32) NOT NULL,
    name VARCHAR(40) NOT NULL,
    breed VARCHAR(60) NOT NULL,
    fur_color VARCHAR(40) NOT NULL,
    level TINYINT UNSIGNED NOT NULL DEFAULT 1,
    affection SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    selected TINYINT(1) NOT NULL DEFAULT 0,
    last_interacted_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_partner_pet_public_id (public_id),
    KEY idx_partner_pet_user_selected (user_id, selected),
    CONSTRAINT fk_partner_pet_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT chk_partner_pet_species CHECK (species_code IN ('CAT', 'DOG', 'HAMSTER', 'SNAKE', 'RABBIT', 'BIRD', 'TURTLE', 'FOX')),
    CONSTRAINT chk_partner_pet_level CHECK (level BETWEEN 1 AND 20),
    CONSTRAINT chk_partner_pet_affection CHECK (affection BETWEEN 0 AND 999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE partner_shop_item (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id VARCHAR(40) NOT NULL,
    item_type VARCHAR(16) NOT NULL,
    species_code VARCHAR(32) NULL,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(240) NOT NULL,
    price INT UNSIGNED NOT NULL,
    affection_gain SMALLINT UNSIGNED NOT NULL,
    template_source VARCHAR(120) NOT NULL,
    active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_partner_shop_item_public_id (public_id),
    KEY idx_partner_shop_item_type (item_type, active),
    CONSTRAINT chk_partner_shop_item_type CHECK (item_type IN ('FOOD', 'DECOR')),
    CONSTRAINT chk_partner_shop_item_price CHECK (price BETWEEN 1 AND 9999),
    CONSTRAINT chk_partner_shop_item_affection CHECK (affection_gain BETWEEN 1 AND 200)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE partner_purchase (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    pet_id BIGINT UNSIGNED NOT NULL,
    item_id BIGINT UNSIGNED NOT NULL,
    coin_delta INT NOT NULL,
    affection_delta SMALLINT UNSIGNED NOT NULL,
    purchased_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_partner_purchase_public_id (public_id),
    KEY idx_partner_purchase_user_time (user_id, purchased_at),
    CONSTRAINT fk_partner_purchase_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_partner_purchase_pet FOREIGN KEY (pet_id) REFERENCES partner_pet (id),
    CONSTRAINT fk_partner_purchase_item FOREIGN KEY (item_id) REFERENCES partner_shop_item (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO partner_shop_item (public_id, item_type, species_code, name, description, price, affection_gain, template_source) VALUES
('pet-food-salmon-bento', 'FOOD', 'CAT', '三文鱼小便当', '适合猫咪的软糯鱼肉饭团。', 18, 14, 'Kenney Animal Pack / OpenGameArt 2D Foods'),
('pet-food-bone-cookie', 'FOOD', 'DOG', '骨头小饼干', '适合狗狗的脆脆训练奖励。', 16, 12, 'Kenney Animal Pack / CraftPix Pet Icons'),
('pet-food-seed-cup', 'FOOD', 'HAMSTER', '葵花籽小杯', '适合仓鼠的小份零食。', 12, 10, 'OpenGameArt Food Icons / Kenney UI Pack'),
('pet-food-mouse-jelly', 'FOOD', 'SNAKE', '月光果冻', '给蛇蛇的卡通替代食物，不使用写实猎物。', 20, 15, 'CraftPix Cartoon Pet Props'),
('pet-food-veggie-bowl', 'FOOD', NULL, '彩蔬能量碗', '多数伙伴都喜欢的清爽食物。', 14, 9, 'Kenney Food Kit'),
('pet-decor-ribbon', 'DECOR', NULL, '柔软小领结', '一件轻巧装饰，适合日常互动。', 30, 18, 'Kenney Animal Pack Accessories'),
('pet-decor-cushion', 'DECOR', NULL, '云朵坐垫', '让伙伴待机时更舒服。', 42, 24, 'CraftPix Cozy Room Props'),
('pet-decor-plant', 'DECOR', NULL, '迷你绿植', '给伙伴角落增加一点生命力。', 36, 20, 'OpenGameArt Decorative Props');
